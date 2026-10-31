package com.hmdp.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.SeckillRequest;
import com.hmdp.mapper.SeckillRequestMapper;
import com.hmdp.service.SeckillRequestService;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/** 死信消费者：主消费组重试耗尽后消息会进 %DLQ% 队列，这里收到就做持久终止裁决（把申请 cancel）。
 *  已经成功的申请不会被改掉，因为 cancel 只对 INIT/READY 生效；真正的库存补偿由独立任务执行。 */
@Component
@Slf4j
@RocketMQMessageListener(
        topic = "%DLQ%${hmdp.mq.consumer-group}",
        consumerGroup = "${hmdp.mq.dlq-consumer-group}",
        maxReconsumeTimes = 3)
public class SeckillDeadLetterConsumer implements RocketMQListener<String> {
    @Resource private ObjectMapper json;
    @Resource private SeckillRequestMapper requests;
    @Resource private SeckillRequestService lifecycle;

    @Override
    public void onMessage(String body) {
        Long orderId = null;
        try {
            OrderCreateMessage message = json.readValue(body, OrderCreateMessage.class);
            if (message == null) {
                throw new IllegalArgumentException("死信内容为空");
            }
            orderId = message.getOrderId();
            SeckillRequest request = orderId == null ? null : requests.selectById(orderId);
            if (!message.matches(request)) {
                throw new IllegalArgumentException("死信身份需要核查");
            }
            // 消息重试耗尽说明这单已经推进不下去，直接终止，让补偿任务去还库存。
            lifecycle.cancel(orderId, "消息消费重试耗尽，申请已终止");
        } catch (Exception error) {
            // 只记处理阶段、身份和异常根因，不把消息正文或连接凭据写进日志，避免泄露。
            log.error("死信裁决失败，请核查处理消费组的重试/死信队列，orderId={}", orderId, error);
            throw new IllegalStateException("死信处理未完成", error);
        }
    }
}
