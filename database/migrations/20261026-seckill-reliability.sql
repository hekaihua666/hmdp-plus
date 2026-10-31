-- 增量升级：先停后端，再由 setup_seckill_mq.py --apply 按元数据幂等执行。
ALTER TABLE tb_seckill_request
    ADD COLUMN reservation_state VARCHAR(8) NOT NULL DEFAULT 'UNKNOWN'
    COMMENT 'NONE/UNKNOWN/HELD' AFTER state;

-- 旧 INIT/CANCELLING 无法仅凭业务状态推断预占结果，保留 UNKNOWN 等证据核查。
UPDATE tb_seckill_request
SET reservation_state = CASE WHEN state IN ('READY','SUCCEEDED') THEN 'HELD' ELSE 'NONE' END
WHERE reservation_state='UNKNOWN' AND state IN ('READY','SUCCEEDED','FAILED');

CREATE INDEX idx_request_deadline ON tb_seckill_request (state, deadline);
