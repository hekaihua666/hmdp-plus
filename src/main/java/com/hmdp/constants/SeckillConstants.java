package com.hmdp.constants;

/** 秒杀申请与预占的持久化状态，供业务服务、调度器和查询视图统一使用。 */
public final class SeckillConstants {
    public static final String INIT = "INIT";//申请已持久化，还没碰 Redis
    public static final String READY = "READY";//确认已预占，可以发 MQ、可以落单
    public static final String SUCCEEDED = "SUCCEEDED";//申请成功，等待支付
    public static final String CANCELLING = "CANCELLING";//取消中，等待库存归还
    public static final String FAILED = "FAILED";//申请失败

    /** 调 Redis 前先提交 UNKNOWN，成功后记 HELD；NONE 仅表示确定未预占。 */
    public static final String RESERVATION_NONE = "NONE";//表示确定未预占
    public static final String RESERVATION_UNKNOWN = "UNKNOWN";//表示未知状态，可能已预占也可能未预占
    public static final String RESERVATION_HELD = "HELD";//表示已预占

    /** 超期终止的统一文案：受理入口、恢复任务与查询视图共用，避免各处措辞漂移。 */
    public static final String TIMEOUT_REASON = "申请处理超时";

    private SeckillConstants() {}
}
