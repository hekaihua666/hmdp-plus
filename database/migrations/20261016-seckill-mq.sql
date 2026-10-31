-- 显式迁移，不能整份重导课程SQL。唯一索引由初始化程序核实重复记录及已有结构后添加。
CREATE TABLE IF NOT EXISTS tb_seckill_request (
    order_id BIGINT NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    voucher_id BIGINT UNSIGNED NOT NULL,
    state VARCHAR(16) NOT NULL COMMENT 'INIT/READY/SUCCEEDED/CANCELLING/FAILED',
    reservation_state VARCHAR(8) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'NONE/UNKNOWN/HELD',
    send_attempts INT NOT NULL DEFAULT 0,
    next_send_time DATETIME NOT NULL,
    last_sent_time DATETIME NULL,
    deadline DATETIME NOT NULL,
    last_error VARCHAR(160) NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (order_id),
    KEY idx_request_recovery (state, next_send_time),
    KEY idx_request_deadline (state, deadline),
    KEY idx_request_user (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
