-- 新建数据库：导入 schema.sql 后再导入本文件。商户账号由显式初始化程序创建。
CREATE TABLE IF NOT EXISTS tb_merchant (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username VARCHAR(32) NOT NULL,
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，不存明文',
    name VARCHAR(64) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    session_version BIGINT NOT NULL DEFAULT 0 COMMENT '封禁时递增，使全部旧登录态失效',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
