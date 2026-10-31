package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * <p>
 * Redis实现全局唯一ID工具类
 * </p>
 *
 * 结构：时间戳（高32位）+ 序列号（低32位）
 * 时间戳：从基准时间（2022-01-01 00:00:00）到当前的秒数
 * 序列号：Redis的INCR命令按天自增，原子性保证并发不重复
 *
 * @author 虎哥
 */
@Component
public class RedisIdWorker {
    /**
     * 开始时间戳：2022-01-01 00:00:00 UTC
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1.获取当前日期，精确到天（每天一个key，计数器每天重置）
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3.拼接并返回：时间戳左移32位，低32位放序列号
        return timestamp << COUNT_BITS | count;
    }
}
