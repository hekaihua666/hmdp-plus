#!/usr/bin/env bash
# 为秒杀压测准备一批"干净用户"：造用户 + 直接往 Redis 写登录 token。
# 为什么要造用户：一人一单是按 (user_id, voucher_id) 拦的，同一个用户重复请求会被预检直接拒掉，
# 那样压出来的只是"拒绝"的吞吐。要压真正的抢购链路，得让每个请求换一个没抢过的用户。
set -euo pipefail

CONTAINER_MYSQL=${CONTAINER_MYSQL:-hmdp-mysql}
CONTAINER_REDIS=${CONTAINER_REDIS:-hmdp-redis}
COUNT=${1:-200}
PREFIX=${2:-1390000}
OUT=${3:-/opt/hmdp/deploy/loadtest/tokens.txt}

echo "==> 准备 $COUNT 个压测用户（手机号前缀 $PREFIX）"

# 1. 造用户。INSERT IGNORE 保证反复执行不会报主键/唯一键冲突。
{
  for ((i = 1; i <= COUNT; i++)); do
    phone=$(printf "%s%04d" "$PREFIX" "$i")
    echo "INSERT IGNORE INTO tb_user(phone,nick_name,password,icon,create_time,update_time) VALUES('$phone','压测用户$i','','',NOW(),NOW());"
  done
} | docker exec -i "$CONTAINER_MYSQL" sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 hmdp' 2>/dev/null

# 2. 查出这批用户的 id，按 id 升序，和上面的序号对齐。
IDS=$(docker exec -i "$CONTAINER_MYSQL" sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B hmdp' 2>/dev/null <<SQL
SELECT id FROM tb_user WHERE phone LIKE '${PREFIX}%' ORDER BY id;
SQL
)
TOTAL=$(printf "%s\n" "$IDS" | grep -c . || true)
echo "==> 库里现有 $TOTAL 个压测用户"

# 3. 每个用户配一个 token，直接写进 Redis（绕过短信验证码）。
#    RefreshTokenInterceptor 读的就是这个 Hash，字段名必须是 id/nickName/icon。
rm -f "$OUT"
i=0
while read -r uid; do
  [ -z "$uid" ] && continue
  token=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
  docker exec "$CONTAINER_REDIS" redis-cli HSET "login:token:$token" id "$uid" nickName "load_$uid" icon "" >/dev/null
  docker exec "$CONTAINER_REDIS" redis-cli EXPIRE "login:token:$token" 36000 >/dev/null
  echo "$token" >> "$OUT"
  i=$((i + 1))
done <<< "$IDS"

echo "==> 已写入 $i 条 token -> $OUT"
head -n 3 "$OUT"
