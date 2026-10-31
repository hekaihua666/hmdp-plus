package com.hmdp.utils;

/**
 * 锁的基本接口
 * 实现分布式锁时需要实现的两个基本方法：
 * - 获取锁：互斥（同一时刻只能一个线程持有）+ 非阻塞（尝试一次，成功返回true，失败返回false）
 * - 释放锁：手动释放 + 超时释放（获取锁时设置过期时间，防止死锁）
 *
 * @author 虎哥
 */
public interface ILock {

    /**
     * 尝试获取锁（非阻塞）
     * @param timeoutSec 锁持有的超时时间（秒），到期自动释放
     * @return true代表获取锁成功，false代表获取锁失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
