package com.hmdp.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.ShopSyncEvent;
import com.hmdp.mapper.ShopSyncEventMapper;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/** 商户同步的死信消费者：主消费组重试耗尽后消息进 %DLQ% 队列，这里只记录日志留人工核查。 */
@Component
@Slf4j
@RocketMQMessageListener(
        topic = "%DLQ%${hmdp.mq.shop-sync-consumer-group}",
        consumerGroup = "${hmdp.mq.shop-sync-dlq-consumer-group}",
        maxReconsumeTimes = 3)
public class ShopSyncDeadLetterConsumer implements RocketMQListener<String> {
    @Resource private ObjectMapper json;
    @Resource private ShopSyncEventMapper events;

    @Override
    public void onMessage(String body) {
        Long shopId = null;
        try {
            ShopSyncMessage message = json.readValue(body, ShopSyncMessage.class);
            if (message == null) {
                throw new IllegalArgumentException("死信内容为空");
            }
            shopId = message.getShopId();
            ShopSyncEvent event = shopId == null ? null : events.selectById(shopId);
            if (!message.matches(event)) {
                throw new IllegalArgumentException("死信身份需要核查");
            }
            // 同步失败不阻断商户写库；把错误留在 Outbox 记录里，等人工核查后重新触发发送即可。
            events.consumeFailed(shopId, message.getRevision(), "消费重试耗尽，请核查ES与MQ后重新触发");
            log.error("商户同步死信待人工核查，shopId={}", shopId);
        } catch (Exception error) {
            log.error("商户同步死信裁决失败，shopId={}", shopId, error);
            throw new IllegalStateException("死信处理未完成", error);
        }
    }
}
