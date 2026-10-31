package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 基于StringRedisTemplate封装的缓存工具类
 * 满足需求：
 * 1. 将任意Java对象序列化为json并存储到string类型的key中，可设置TTL过期时间
 * 2. 将任意Java对象序列化为json并存储到string类型的key中，可设置逻辑过期时间（解决缓存击穿）
 * 3. 根据key查询缓存并反序列化为指定类型，利用缓存空值方式解决缓存穿透
 * 4. 根据key查询缓存并反序列化为指定类型，利用逻辑过期解决缓存击穿
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透解决方案：缓存空值。
     *
     * <p>缓存穿透 = 查询一个数据库里根本不存在的 id，缓存永远 miss，每次都打到数据库。
     * 方案：查库发现不存在时，也往缓存里写一个「空值」（空字符串，TTL 较短），
     * 这样下次再查同一个不存在的 id，就能命中这个空值缓存，不再打数据库。</p>
     *
     * @param keyPrefix 缓存 key 前缀（如 cache:shop:）
     * @param id        业务 id（拼成完整 key）
     * @param type      反序列化目标类型
     * @param dbFallback 数据库兜底查询函数（缓存 miss 时调用）
     * @param time/unit 正常数据的缓存过期时间
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在：非空串 = 命中了正常数据
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接反序列化返回
            return JSONUtil.toBean(json, type);
        }
        // 命中「空串」= 之前查过且确认不存在，直接返回 null，不再打库（防穿透）
        if (json != null) {
            return null;
        }

        // 4.缓存完全 miss，查数据库
        R r = dbFallback.apply(id);
        // 5.数据库也没有：写入空值缓存（TTL 较短），下次命中空值直接返回
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6.数据库有：写入正常缓存并返回
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 缓存击穿解决方案：逻辑过期（热点 key 永不物理删除，过期字段存 value 里）。
     *
     * <p>缓存击穿 = 热点 key 在过期瞬间被大量并发请求打库。逻辑过期做法：
     * key 不设 TTL（永不物理删除），把「过期时间」作为字段存在 value 里，
     * 由应用自己判断是否过期。已过期时先返回旧数据，再异步开线程查库重建缓存，
     * 保证用户体验（不阻塞等待）。</p>
     *
     * <p>注意：需要数据提前预热（缓存里没有数据时直接返回 null，不会自动查库）。</p>
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在：缓存里完全没有（未预热）直接返回 null，不做自动查库
        if (StrUtil.isBlank(json)) {
            return null;
        }
        // 3.命中：反序列化 RedisData（含 data + expireTime 两个字段）
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 4.判断是否逻辑过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期，直接返回
            return r;
        }
        // 已过期：需要缓存重建，但先返回旧数据（不阻塞用户）
        // 5.获取互斥锁：只让一个请求去重建，其余直接拿旧数据返回
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            // 拿到锁：异步开线程查库重建，避免阻塞当前请求
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R newR = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 无论拿没拿到锁，都先返回旧数据（保证响应速度）
        return r;
    }

    /**
     * 缓存击穿解决方案：互斥锁。
     *
     * <p>缓存击穿 = 热点 key 过期瞬间大量并发打库。互斥锁做法：缓存 miss 后先抢
     * SETNX 锁，抢到的那个请求负责查库重建缓存，抢不到的休眠 50ms 后递归重试
     * （此时缓存大概率已被重建，重试时能命中）。保证同一时刻只有一个请求打库。</p>
     */

    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.命中正常数据直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, type);
        }
        // 命中空值缓存直接返回 null（防穿透）
        if (shopJson != null) {
            return null;
        }

        // 缓存 miss：开始互斥锁重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                // 抢锁失败：休眠 50ms 后递归重试，等持锁者重建完成后重试即可命中
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 抢锁成功：查库重建
            r = dbFallback.apply(id);
            if (r == null) {
                // 数据库也没有：写空值缓存，防穿透
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 写正常缓存
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 无论成败都释放锁，避免死锁
            unlock(lockKey);
        }
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
