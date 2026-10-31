package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 秒杀申请，同时承担受理凭证和待发布记录。
 *
 * <p>旧 Stream 已有 Lua XADD 和 pending-list；迁移后由本表记录跨组件处理进度，
 * 支持结果查询、补发和补偿。业务状态为 INIT → READY → SUCCEEDED，失败走 CANCELLING → FAILED。
 */
@Data
@TableName("tb_seckill_request")
public class SeckillRequest {
    /** 时间戳与 Redis INCR 序列生成的身份，申请、订单和消息共同使用。 */
    @TableId("order_id")
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private String state;

    /** NONE / UNKNOWN / HELD，进入补偿后仍保留预占证据，防止凭据丢失时误判完成。 */
    private String reservationState;
    // 发送尝试次数
    private Integer sendAttempts;

    /** 到期处理时间，同时用于发送租期与失败退避。 */
    private LocalDateTime nextSendTime;
    private LocalDateTime lastSentTime;
    private LocalDateTime deadline;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 最近申请联表查询的展示字段。 */
    @TableField(exist = false)
    private String voucherTitle;
}
