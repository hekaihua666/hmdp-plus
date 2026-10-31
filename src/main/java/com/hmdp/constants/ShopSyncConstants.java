package com.hmdp.constants;

/** 商户同步 ES 的 Outbox 状态，只有"待发送"和"已发送"两态，不引入多余中间态。 */
public final class ShopSyncConstants {
    public static final String PENDING = "PENDING";//已落库待发送
    public static final String SENT = "SENT";//已确认发送

    private ShopSyncConstants() {}
}
