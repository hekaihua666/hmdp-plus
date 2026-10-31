-- 平台管理员独立于消费者和商户；账号由显式初始化工具创建，不开放公开注册。
CREATE TABLE IF NOT EXISTS tb_admin (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username VARCHAR(32) NOT NULL,
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt哈希，不存明文',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用，0停用',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
