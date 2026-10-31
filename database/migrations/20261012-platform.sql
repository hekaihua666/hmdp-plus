-- 已有数据库执行一次；PlatformSetupTest会先检查列是否存在，支持安全重复执行。
ALTER TABLE tb_merchant ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0
    COMMENT '封禁时递增，解封不恢复旧token';
