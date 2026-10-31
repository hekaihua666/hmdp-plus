package com.hmdp.mq;

import com.hmdp.entity.ShopSyncEvent;
import lombok.Data;

/** 商户同步 ES 的消息体，带商户 id 和发送版本；真正的同步内容以数据库里的最新商户为准。 */
@Data
public class ShopSyncMessage {
    private int version = 1;
    private String eventId;//幂等键，固定为 SHOP_SYNC:shopId
    private Long shopId;
    /** 本次发送的 Outbox 版本，仅用于确认和死信归属，不用于给 ES 数据打版本。 */
    private Long revision;

    private static final String EVENT_PREFIX = "SHOP_SYNC:";

    private static String eventIdOf(Long shopId) {
        return EVENT_PREFIX + shopId;
    }

    /** 从 shopId 生成一条消息，补发时沿用同一个幂等键。 */
    public static ShopSyncMessage of(Long shopId, Long revision) {
        ShopSyncMessage message = new ShopSyncMessage();
        message.setShopId(shopId);
        message.setRevision(revision);
        message.setEventId(eventIdOf(shopId));
        return message;
    }

    /** 校验协议版本和 shopId 是否与 Outbox 记录一致；授权由部署侧负责。 */
    public boolean matches(ShopSyncEvent event) {
        return event != null && version == 1
                && event.getShopId().equals(shopId)
                && eventIdOf(shopId).equals(eventId);
    }
}
