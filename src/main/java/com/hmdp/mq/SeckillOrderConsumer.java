package com.hmdp.mq;

import com.hmdp.service.SeckillRequestService;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/** 下单消息的消费者，接到消息就交给 SeckillRequestService.consume() 落单。
 *  事务正常返回就确认消息；异常直接抛给 SDK 触发重试，持久终止后的补偿由独立任务处理。 */
@Component
@Slf4j
@RocketMQMessageListener(
        topic = "${hmdp.mq.topic}",
        consumerGroup = "${hmdp.mq.consumer-group}",
        maxReconsumeTimes = 3)
public class SeckillOrderConsumer implements RocketMQListener<OrderCreateMessage> {
    @Resource private SeckillRequestService lifecycle;

    @Override
    public void onMessage(OrderCreateMessage message) {
        lifecycle.consume(message);
        log.info("秒杀消息已处理，orderId={}", message.getOrderId());
    }
}
