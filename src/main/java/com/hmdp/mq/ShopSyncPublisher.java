package com.hmdp.mq;

import static com.hmdp.constants.ShopSyncConstants.PENDING;

import com.hmdp.entity.ShopSyncEvent;
import com.hmdp.mapper.ShopSyncEventMapper;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

/** 商户同步消息的发布器：先抢发送租期再同步发送，结果未知时退避重试，消费端重读库写 ES 兜底幂等。 */
@Service
@Slf4j
public class ShopSyncPublisher {
    @Resource private RocketMQTemplate template;
    @Resource private ShopSyncEventMapper events;

    @Value("${hmdp.mq.shop-sync-topic}")
    private String topic;

    /** 发一条同步消息：抢到发送权才发，发送成功置 SENT，结果说不清就记错误等下一轮补发。 */
    public void publish(ShopSyncEvent event) {
        if (event == null || !PENDING.equals(event.getState())
                || events.claimSend(event.getShopId(), event.getRevision()) != 1) {//没抢到租期
            return;
        }
        Long shopId = event.getShopId();
        try {
            SendResult result = template.syncSend(topic,
                    MessageBuilder.withPayload(ShopSyncMessage.of(shopId, event.getRevision()))
                            .setHeader(RocketMQHeaders.KEYS, shopId.toString()).build());
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("Broker未确认发送");
            }
            // 旧发送完成时若商户已有新更新，条件 UPDATE 返回 0，新版本继续保持待发送。
            events.sent(shopId, event.getRevision());
            log.info("商户同步消息已确认发送，shopId={}", shopId);
        } catch (Exception error) {
            // 发送结果说不清，只记错误退避重试；消费端会重读 MySQL 最新数据，重复发送无副作用。
            log.warn("商户同步等待补发，shopId={}", shopId, error);
            events.sendFailed(shopId, event.getRevision(), "消息发送结果未知，等待补发");
        }
    }
}
