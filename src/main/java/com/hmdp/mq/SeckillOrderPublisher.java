package com.hmdp.mq;

import static com.hmdp.constants.SeckillConstants.READY;

import com.hmdp.entity.SeckillRequest;
import com.hmdp.mapper.SeckillRequestMapper;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

/** 后台同步发送下单消息的发布器。发送结果未知时保留 READY 状态，靠定时任务用同一个订单号重发，靠消费端幂等兜底。
 *  同步发送只是等 Broker 确认收到了，并不等于已经落单成功。 */
@Service
@Slf4j
public class SeckillOrderPublisher {
    @Resource private RocketMQTemplate template;
    @Resource private SeckillRequestMapper requests;

    @Value("${hmdp.mq.topic}")
    private String topic;

    @Value("${hmdp.mq.publish-interval-seconds:60}")
    private int publishInterval;

    /** 发一条下单消息：先抢发送租期（claimSend 返回 1 才轮到我发），再同步发送，成功就记 sent，失败就记 sendFailed 等补发。
     *  扫描记录只提供消息身份，能否发送由数据库的条件 UPDATE 裁决；即使确认丢失，消费端可能已落单，这里只记进度、不退库存。 */
    public void publish(SeckillRequest request) {
        // 抢发送权：只有把 next_send_time 成功往后推的那一个实例才返回 1，其余并发实例直接跳过。
        if (request == null || !READY.equals(request.getState())
                || requests.claimSend(request.getOrderId()) != 1) {//没抢到租期
            return;
        }
        Long orderId = request.getOrderId();
        try {
            SendResult result = template.syncSend(topic,
                    MessageBuilder.withPayload(OrderCreateMessage.of(request))
                            .setHeader(RocketMQHeaders.KEYS, orderId.toString()).build());
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("Broker未确认发送");
            }
            // 发送成功，把下次核对时间推进到不超过 deadline，让定时任务晚点再查。
            requests.sent(orderId, publishInterval);
            log.info("秒杀消息已确认发送，orderId={}", orderId);
        } catch (Exception error) {
            // 结果说不清时只记错误、退避重试，绝不判失败也不退库存，防止重复消费时库存已还导致超卖。
            log.warn("抢购事件等待补发，orderId={}", orderId, error);
            requests.sendFailed(orderId, "消息发送结果未知，等待补发");
        }
    }
}
