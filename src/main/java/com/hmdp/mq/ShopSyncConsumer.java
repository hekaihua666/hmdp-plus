package com.hmdp.mq;

import com.hmdp.service.IShopSearchService;
import java.io.IOException;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/** 商户同步消息的消费者：重读 MySQL 最新商户写 ES，成功返回=确认消息，异常抛出触发重试。 */
@Component
@Slf4j
@RocketMQMessageListener(
        topic = "${hmdp.mq.shop-sync-topic}",
        consumerGroup = "${hmdp.mq.shop-sync-consumer-group}",
        maxReconsumeTimes = 3)
public class ShopSyncConsumer implements RocketMQListener<ShopSyncMessage> {
    @Resource private IShopSearchService shopSearchService;

    @Override
    public void onMessage(ShopSyncMessage message) {
        // 按商户 id 重读数据及其版本；ES 外部版本比较负责拒绝迟到的旧数据。
        try {
            shopSearchService.syncById(message.getShopId());
        } catch (IOException error) {
            // 同步失败抛运行时异常，让 RocketMQ 触发重试；重试耗尽后进死信队列留人工核查。
            throw new IllegalStateException("商户同步 ES 失败，等待重试", error);
        }
        log.info("商户同步 ES 完成，shopId={}", message.getShopId());
    }
}
