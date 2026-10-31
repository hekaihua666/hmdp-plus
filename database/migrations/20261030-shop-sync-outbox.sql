-- 商户同步 ES 的 Outbox 表：商户写库事务里同时落一条 PENDING 记录，定时任务扫出发 MQ。
-- 主键用 shop_id，同一商户连续更新只留最新一条（ON DUPLICATE KEY UPDATE 重置为 PENDING）。
CREATE TABLE IF NOT EXISTS tb_shop_sync_event (
    shop_id BIGINT NOT NULL COMMENT '商户id，主键',
    state VARCHAR(16) NOT NULL COMMENT 'PENDING待发送/SENT已发送',
    next_send_time DATETIME NOT NULL COMMENT '下次尝试发送时间，兼作发送租期与失败退避到期点',
    last_error VARCHAR(512) DEFAULT NULL COMMENT '最近一次失败原因',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (shop_id),
    KEY idx_shop_sync_recovery (state, next_send_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户同步ES待发送事件';
