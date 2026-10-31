package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 基于Redis的手写分布式锁
 *
 * 演进要点：
 * 1. 版本一：set nx ex 加锁 + del 释放，可防止死锁，但释放锁时可能误删别人的锁（线程1卡顿后锁超时，线程2拿到锁，线程1醒来误删线程2的锁）
 * 2. 版本二：加锁时存入当前线程标示（ID_PREFIX + threadId），释放锁前先比对锁中的标示是否属于自己，不一致则不删除
 * 3. 版本三：拿锁-比锁-删锁 不是原子操作，中间可能再次出现误删（线程1比完正准备删时锁刚好超时），因此用 Lua 脚本将"取锁、比锁、删锁"合并为一次原子执行
 *
 * @author 虎哥
 */
public class SimpleRedisLock implements ILock {

    private final String name;
    private final StringRedisTemplate stringRedisTemplate;

    /** 锁的 key 前缀 */
    private static final String KEY_PREFIX = "lock:";
    /** 线程标示前缀（JVM级唯一），用于区分不同线程持有的锁，避免误删 */
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    /** Lua 解锁脚本（拿锁-比锁-删锁 原子执行） */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();//Spring Data Redis 提供的"Redis 脚本封装对象"。
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标示（本机唯一，保证同一线程多次加锁标示一致）
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 利用 set nx ex 加锁：key 不存在才写入（互斥），并设置过期时间（超时自动释放，防死锁）
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 调用 Lua 脚本：先取锁中的标示，与当前线程标示比对，一致才删除（原子，防止误删别人的锁）
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }
}
