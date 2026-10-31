package com.hmdp.listener;

import com.hmdp.event.ShopChangedEvent;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.annotation.Resource;

/**
 * 商户变更事件监听器
 * 事务提交后失效 Redis 详情缓存；ES 同步已改为 Outbox + MQ 可靠投递，不再在这里处理。
 *
 * 注意：
 * 1. 事务提交后同步，避免回滚数据被读走
 * 2. 此处只负责删除详情缓存，ES 同步由 ShopSyncPublisher/Consumer 走 MQ 完成
 */
@Component
@Slf4j
public class ShopChangedEventListener {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 提交后失效详情缓存；附近检索统一由ES承担，不维护第二份GEO索引。
     *
     * 此处没有@Async：正常情况下，商户写请求返回前已尝试删除旧详情缓存。
     * 不能在事务提交前删除：其他线程可能在提交前读到旧数据库值并回填成旧缓存。
     * 删除后下一次消费者详情查询再按原CacheClient策略回源，不在此处重建完整缓存。
     * AFTER_COMMIT时数据库已提交，Redis异常不能让原事务重新回滚，只能记录后处理。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void updateRedisAfterCommit(ShopChangedEvent event) {
        Long shopId = event.getShopId();
        try {
            // 新增也删除可能存在的"查无店铺"空值缓存。
            stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shopId);
        } catch (Exception e) {
            // 独立监听器的异常被记录，不影响其他任务。
            log.error("商户提交后失效Redis详情缓存失败，shopId={}", shopId, e);
        }
    }
}
