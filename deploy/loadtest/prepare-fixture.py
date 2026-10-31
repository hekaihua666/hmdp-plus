#!/usr/bin/env python3
"""在专用测试环境创建一张不关联门店的短期秒杀券及新测试用户。"""
import json
import secrets
import subprocess
from datetime import datetime
from pathlib import Path

COUNT = 50
STOCK = 20

def docker(*args, sql=None):
    # 密码只由 MySQL 容器内部的环境变量展开，不在主机命令行上拼接。
    return subprocess.run(["docker", "exec", "-i", *args], input=sql, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout

def main():
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    directory = Path("/opt/hmdp/target/loadtest") / ("fixture-" + stamp + "-" + secrets.token_hex(3))
    directory.mkdir(parents=True, mode=0o700)
    # 随机手机号只用于测试；唯一索引冲突时事务失败，不复用现有用户。
    phones = set()
    while len(phones) < COUNT:
        phones.add("199" + str(secrets.randbelow(100000000)).zfill(8))
    phones = sorted(phones)
    inserts = "\n".join("INSERT INTO tb_user(phone,nick_name,password,icon) VALUES ('%s','load-test','','');" % phone for phone in phones)
    phone_list = ",".join("'" + phone + "'" for phone in phones)
    sql = """START TRANSACTION;
INSERT INTO tb_voucher(shop_id,title,pay_value,actual_value,type,status)
VALUES(NULL,'HMDP独立秒杀测试券',100,100,1,1);
SET @v = LAST_INSERT_ID();
INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time)
VALUES(@v,%d,NOW() - INTERVAL 1 MINUTE,NOW() + INTERVAL 1 HOUR);
%s
SELECT 'VOUCHER',@v;
SELECT 'USER',id FROM tb_user WHERE phone IN (%s) ORDER BY id;
COMMIT;
""" % (STOCK, inserts, phone_list)
    try:
        result = docker("hmdp-mysql", "sh", "-c", 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -N -B hmdp', sql=sql)
    except subprocess.CalledProcessError:
        raise SystemExit("数据库创建失败，事务未完成；检查数据库状态及测试手机号冲突。")
    rows = [line.split("\t") for line in result.splitlines() if "\t" in line]
    voucher = next(int(row[1]) for row in rows if row[0] == "VOUCHER")
    users = [int(row[1]) for row in rows if row[0] == "USER"]
    # 先保存 ID，Redis 准备失败时仍能追踪已经提交的测试数据。
    manifest = {"voucherId": voucher, "initialStock": STOCK, "userIds": users, "tokenTtlSeconds": 1800}
    (directory / "fixture.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    if len(users) != COUNT:
        raise SystemExit("测试用户数量异常；已创建数据记录在 " + str(directory / "fixture.json"))
    token_file = directory / "tokens.txt"
    token_file.touch(mode=0o600, exist_ok=False)
    try:
        with token_file.open("w", encoding="utf-8") as f:
            for uid in users:
                token = secrets.token_hex(16)
                docker("hmdp-redis", "redis-cli", "HSET", "login:token:" + token, "id", str(uid), "nickName", "load-test", "icon", "")
                docker("hmdp-redis", "redis-cli", "EXPIRE", "login:token:" + token, "1800")
                f.write(token + "\n")
    except subprocess.CalledProcessError:
        raise SystemExit("Redis 准备失败；测试券和用户 ID 已保存在 " + str(directory / "fixture.json") + "，先核查，不要重新运行。")
    print("测试券ID:", voucher)
    print("用户数量:", len(users))
    print("初始库存:", STOCK)
    print("测试文件目录:", directory)
    print("令牌文件不应分享；测试结果和数据将保留以便核查。")

if __name__ == "__main__":
    import sys
    if "--apply" not in sys.argv:
        raise SystemExit("仅用于专用测试环境；明确创建测试用户和测试券时加 --apply。")
    main()
