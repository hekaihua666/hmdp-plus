package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商户同步 ES 的 Outbox 记录。
 *
 * <p>商户写库的事务里同时落一条本记录，事务提交后由定时任务扫出 PENDING 记录发 MQ，
 * 消费端再重读 MySQL 最新数据写 ES。这样"改库"和"要同步 ES"是同一笔事务，崩溃也不会丢通知。</p>
 */
@Data
@TableName("tb_shop_sync_event")
public class ShopSyncEvent {
    /** 商户 id 就是这条记录的主键：同一商户连续更新只留最新一条，天然合并。 */
    @TableId("shop_id")
    private Long shopId;

    /** 待发送或已发送；发送成功才置 SENT。 */
    private String state;

    /** 商户每次提交递增；发送确认只能修改自己领取的版本。 */
    private Long revision;

    /** 下次尝试发送的时间，同时充当"发送租期"和"失败退避"的到期点。 */
    private LocalDateTime nextSendTime;

    /** 发送失败/结果未知时留下的最近一次原因，供人工核查。 */
    private String lastError;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 联表查询时带出的商户名，方便人工在日志里认是哪家店。 */
    @TableField(exist = false)
    private String shopName;
}
