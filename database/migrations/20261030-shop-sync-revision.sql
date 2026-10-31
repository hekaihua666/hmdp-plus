-- 在原 Outbox 建表脚本之后执行一次；升级前停止应用，升级后再启动。
-- 使用高于旧索引内部版本的起点，兼容已有 ES 文档；版本持久保留，不清空重建 Outbox。
ALTER TABLE tb_shop_sync_event
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 1000000000001 COMMENT '商户同步版本，每次商户提交递增';
