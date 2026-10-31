"""清理结束已久活动的终态申请和请求标记；默认预览，--apply 才执行。

默认保留30天，且至少7天；保留期应长于部署的消息保留与人工重放窗口。
保留订单、购买归属、库存和所有非终态申请。执行前将候选记录备份到忽略目录。
"""
import argparse
import json
from datetime import datetime, timedelta
from pathlib import Path

from seed_demo_data import ROOT, connections

# 只删除本申请的预期终态标记，不清空 owners 或整个秒杀 key 空间。
REMOVE_MARKER = """
local value = redis.call('get', KEYS[1])
if not value then return 1 end
if value ~= ARGV[1] then return 0 end
redis.call('del', KEYS[1])
return 1
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--retention-days", type=int, default=30)
    parser.add_argument("--limit", type=int, default=200)
    parser.add_argument("--backup-dir", type=Path, default=ROOT / "target/mq-cleanup")
    args = parser.parse_args()
    if args.retention_days < 7 or not 1 <= args.limit <= 1000:
        parser.error("保留期至少7天，单次清理数量须在1到1000之间")
    cutoff = datetime.now() - timedelta(days=args.retention_days)
    sql, cache = connections()
    try:
        with sql.cursor() as cursor:
            cursor.execute(
                "SELECT r.* FROM tb_seckill_request r JOIN tb_seckill_voucher v "
                "ON v.voucher_id=r.voucher_id WHERE r.state IN ('SUCCEEDED','FAILED') "
                "AND r.deadline<%s AND r.update_time<%s AND v.end_time<%s "
                "ORDER BY r.deadline,r.order_id LIMIT %s",
                (cutoff, cutoff, cutoff, args.limit))
            candidates = cursor.fetchall()
            print(json.dumps({"apply": args.apply, "candidates": len(candidates),
                              "cutoff": str(cutoff)}, ensure_ascii=False))
            if not args.apply or not candidates:
                return
            args.backup_dir.mkdir(parents=True, exist_ok=True)
            markers = {str(row["order_id"]): cache.get("seckill:request:" + str(row["order_id"]))
                       for row in candidates}
            backup = args.backup_dir / ("cleanup-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f") + ".json")
            backup.write_text(json.dumps({"requests": candidates, "markers": markers},
                                        ensure_ascii=False, default=str, indent=2), encoding="utf-8")
            removed = skipped = 0
            for row in candidates:
                expected = "HELD" if row["state"] == "SUCCEEDED" else "CLOSED"
                key = "seckill:request:" + str(row["order_id"])
                if cache.eval(REMOVE_MARKER, 1, key, expected) != 1:
                    skipped += 1
                    continue
                # 先清 Redis 再删数据库：中途崩溃只会留下可再次清理的终态记录。
                cursor.execute(
                    "DELETE FROM tb_seckill_request WHERE order_id=%s AND state=%s "
                    "AND deadline<%s AND update_time<%s",
                    (row["order_id"], row["state"], cutoff, cutoff))
                removed += cursor.rowcount
            sql.commit()
            print(json.dumps({"removed": removed, "marker_conflicts": skipped}, ensure_ascii=False))
    finally:
        sql.rollback()
        sql.close()
        cache.close()


if __name__ == "__main__":
    main()
