package com.hmdp.mq;

import com.hmdp.entity.SeckillRequest;
import lombok.Data;

/** 下单通知的消息体，只带业务身份（订单号、用户、券），真正的处理状态以数据库里的申请记录为准。
 *  补发沿用固定的 CREATE_ORDER:orderId 作为幂等键，消费端幂等不依赖传输层的 msgId。 */
@Data
public class OrderCreateMessage {
    private int version = 1;
    private String eventId;//消息的"身份证号"
    private Long orderId;
    private Long userId;
    private Long voucherId;

    private static final String EVENT_PREFIX = "CREATE_ORDER:";

    /** 事件身份的唯一生成规则，生成和校验共用同一个方法，避免两处拼接逻辑漂移。 */
    private static String eventIdOf(Long orderId) {
        return EVENT_PREFIX + orderId;
    }

    /** 从申请记录集中生成一条消息，统一了消息身份，补发时不会换成别的幂等键。 */
    public static OrderCreateMessage of(SeckillRequest request) {
        OrderCreateMessage message = new OrderCreateMessage();
        message.setOrderId(request.getOrderId());
        message.setUserId(request.getUserId());
        message.setVoucherId(request.getVoucherId());
        message.setEventId(eventIdOf(request.getOrderId()));
        return message;
    }

    /** 校验协议版本和业务归属是否和申请记录一致；MQ 的访问授权由部署侧负责，这里只管身份对不对得上。 */
    public boolean matches(SeckillRequest request) {
        return request != null && version == 1
                && request.getOrderId().equals(orderId)
                && request.getUserId().equals(userId)
                && request.getVoucherId().equals(voucherId)
                && eventIdOf(orderId).equals(eventId);
    }
}
