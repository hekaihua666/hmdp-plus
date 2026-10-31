-- 已有数据库的一次性迁移；执行前备份 tb_shop，或使用会检查元数据的 MerchantSetupTest。
-- 先导入 ../merchant-schema.sql，再执行以下语句；不要反复手工执行 ADD COLUMN。
ALTER TABLE tb_shop ADD COLUMN owner_id BIGINT UNSIGNED NULL DEFAULT NULL COMMENT '商户账号归属';
ALTER TABLE tb_shop ADD UNIQUE KEY uk_shop_owner (owner_id);
-- 原课程字段为 UNSIGNED，本版表单接受合法的全球坐标，因此改为有符号。
ALTER TABLE tb_shop MODIFY x DOUBLE NOT NULL, MODIFY y DOUBLE NOT NULL;
