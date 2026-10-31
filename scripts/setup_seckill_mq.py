"""显式迁移秒杀 MQ 表结构；默认检查，--apply 先备份再执行幂等增量升级。

迁移前停止后端。保留旧 Stream、库存、购买资格与已有订单；不导入整份课程 SQL。
"""
import argparse
import json
from datetime import datetime
from pathlib import Path

from seed_demo_data import ROOT, connections


def index_columns(rows, unique_only=False):
    """按索引顺序归并字段，兼容不同索引名与正反序的一人一券唯一约束。"""
    indexes = {}
    for row in rows:
        if not unique_only or row["Non_unique"] == 0:
            indexes.setdefault(row["Key_name"], []).append(
                (row["Seq_in_index"], row["Column_name"]))
    return {name: tuple(column for _, column in sorted(fields))
            for name, fields in indexes.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--backup-dir", type=Path, default=ROOT / "target/mq-migration")
    args = parser.parse_args()
    sql, cache = connections()
    locked = False
    try:
        if not cache.ping():
            raise RuntimeError("Redis不可达，不能核对旧队列")
        groups = cache.xinfo_groups("stream.orders") if cache.exists("stream.orders") else []
        if any(group["pending"] for group in groups):
            raise RuntimeError("旧Stream仍有pending，先核查落单")
        for group in groups:
            last = tuple(map(int, group["last-delivered-id"].split("-")))
            records = cache.xrange("stream.orders", min=group["last-delivered-id"], count=2)
            if any(tuple(map(int, item[0].split("-"))) > last for item in records):
                raise RuntimeError("旧Stream仍有未投递消息，先排空")
        if cache.exists("stream.orders") and not groups and cache.xlen("stream.orders"):
            raise RuntimeError("旧Stream有数据但没有消费组，需核查")

        with sql.cursor() as cursor:
            cursor.execute("SELECT user_id,voucher_id,COUNT(*) AS n FROM tb_voucher_order "
                           "GROUP BY user_id,voucher_id HAVING COUNT(*)>1 LIMIT 1")
            if cursor.fetchone():
                raise RuntimeError("存在重复购买记录，禁止自动添加唯一索引")
            cursor.execute("SHOW INDEX FROM tb_voucher_order")
            unique_indexes = index_columns(cursor.fetchall(), unique_only=True)
            has_unique = any(columns in {("user_id", "voucher_id"), ("voucher_id", "user_id")}
                             for columns in unique_indexes.values())
            cursor.execute("SHOW TABLES LIKE 'tb_seckill_request'")
            has_requests = cursor.fetchone() is not None
            has_evidence = False
            has_deadline_index = False
            if has_requests:
                cursor.execute("SHOW COLUMNS FROM tb_seckill_request LIKE 'reservation_state'")
                has_evidence = cursor.fetchone() is not None
                cursor.execute("SHOW INDEX FROM tb_seckill_request")
                request_indexes = index_columns(cursor.fetchall())
                has_deadline_index = any(columns[:2] == ("state", "deadline")
                                         for columns in request_indexes.values())
                if "idx_request_deadline" in request_indexes and not has_deadline_index:
                    raise RuntimeError("idx_request_deadline同名但字段不符，请先核查")
            print(json.dumps({"apply": args.apply, "unique_user_voucher": has_unique,
                              "reservation_state": has_evidence,
                              "deadline_index": has_deadline_index}, ensure_ascii=False))
            if not args.apply:
                return

            cursor.execute("SELECT GET_LOCK('hmdp_seckill_schema_migration',5) AS acquired")
            locked = cursor.fetchone()["acquired"] == 1
            if not locked:
                raise RuntimeError("其他迁移进程正在执行")

            # DDL 会隐式提交，执行前保存三张相关表与库存的恢复快照。
            args.backup_dir.mkdir(parents=True, exist_ok=True)
            snapshot = {"schema": {}, "data": {}, "redis_stocks": {}}
            tables = ["tb_voucher_order", "tb_seckill_voucher"]
            if has_requests:
                tables.append("tb_seckill_request")
            for table in tables:
                cursor.execute("SHOW CREATE TABLE " + table)
                snapshot["schema"][table] = cursor.fetchone()
                cursor.execute("SELECT * FROM " + table)
                snapshot["data"][table] = cursor.fetchall()
            for key in cache.scan_iter("seckill:stock:*"):
                snapshot["redis_stocks"][key] = cache.get(key)
            filename = "mq-before-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f") + ".json"
            (args.backup_dir / filename).write_text(
                json.dumps(snapshot, ensure_ascii=False, default=str, indent=2), encoding="utf-8")

            cursor.execute((ROOT / "database/migrations/20261016-seckill-mq.sql")
                           .read_text(encoding="utf-8"))
            statements = (ROOT / "database/migrations/20261026-seckill-reliability.sql")\
                .read_text(encoding="utf-8").split(";")
            if not has_requests:
                has_evidence = True
                has_deadline_index = True
            if not has_evidence:
                cursor.execute(statements[0])
            cursor.execute(statements[1])
            if not has_deadline_index:
                cursor.execute(statements[2])
            if not has_unique:
                cursor.execute("ALTER TABLE tb_voucher_order ADD UNIQUE KEY "
                               "uk_order_user_voucher (user_id,voucher_id)")
            sql.commit()
            print("MQ表结构升级完成，未重置原有库存、订单或购买资格。")
    finally:
        if locked:
            with sql.cursor() as cursor:
                cursor.execute("SELECT RELEASE_LOCK('hmdp_seckill_schema_migration')")
        sql.rollback()
        sql.close()
        cache.close()


if __name__ == "__main__":
    main()
