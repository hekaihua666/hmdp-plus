"""显式 Redis 回归：使用随机前缀隔离键，不操作业务库存或购买资格。"""
from pathlib import Path
from time import time
from uuid import uuid4

from seed_demo_data import ROOT, connections


def main():
    sql, cache = connections()
    prefix = "qa:mq-review:" + uuid4().hex + ":"
    keys = [prefix + name for name in ("stock", "owners", "marker")]
    reserve = (ROOT / "src/main/resources/seckill-reserve.lua").read_text(encoding="utf-8")
    release = (ROOT / "src/main/resources/seckill-release.lua").read_text(encoding="utf-8")
    check = (ROOT / "src/main/resources/seckill-check.lua").read_text(encoding="utf-8")
    now = int(time())
    args = ["user-1", "order-1", now - 60, now + 60]
    try:
        cache.set(keys[0], 1)
        assert cache.eval(check, 2, *keys[:2], "user-1", now - 60, now + 60) == 0
        assert cache.get(keys[0]) == "1"
        assert cache.eval(reserve, 3, *keys, *args, "1") == 0
        assert cache.eval(reserve, 3, *keys, *args, "0") == 0
        assert cache.get(keys[0]) == "0"
        assert cache.eval(check, 2, *keys[:2], "user-1", now - 60, now + 60) == 2

        cache.delete(keys[1], keys[2])
        assert cache.eval(release, 3, *keys, "user-1", "order-1", "HELD") == 4
        assert cache.eval(release, 3, *keys, "user-1", "order-1", "UNKNOWN") == 4
        assert cache.eval(reserve, 3, *keys, *args, "0") == 6
        assert cache.get(keys[0]) == "0" and not cache.exists(keys[2])

        cache.hset(keys[1], "user-1", "order-1")
        assert cache.eval(reserve, 3, *keys, *args, "0") == 0
        assert cache.eval(release, 3, *keys, "user-1", "order-1", "HELD") == 0
        assert cache.eval(release, 3, *keys, "user-1", "order-1", "HELD") == 0
        assert cache.get(keys[0]) == "1"
        assert cache.eval(reserve, 3, *keys, *args, "1") == 4

        cache.delete(keys[2])
        assert cache.eval(release, 3, *keys, "user-1", "order-2", "NONE") == 0
        assert cache.get(keys[0]) == "1"
        print("Lua回归通过：只读预检、幂等预占、缺失凭据保护、证据恢复、重复释放及迟到拦截。")
    finally:
        cache.delete(*keys)
        sql.rollback()
        sql.close()
        cache.close()


if __name__ == "__main__":
    main()
