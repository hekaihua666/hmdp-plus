#!/usr/bin/env bash
# 一次跑完四个场景：基线、缓存读、ES 搜索、秒杀。
# 用本机回环地址压，不走公网：公网带宽只有 1~2MB/s，会先于后端成为瓶颈，压出来的数是带宽不是后端。
set -euo pipefail

BASE=${BASE:-http://127.0.0.1:8081}
HERE=$(cd "$(dirname "$0")" && pwd)
PY=${PY:-python3}
STAMP=$(date +%Y%m%d-%H%M%S)
OUTDIR=${OUTDIR:-/opt/hmdp/target/loadtest/$STAMP}
DURATION=${DURATION:-60}
LABEL=${LABEL:-main}
mkdir -p "$OUTDIR"

# 全局参数：并发列表按阶梯递增，每档跑 DURATION 秒，找吞吐拐点。
CONCS=${CONCS:-"10 50 100 200"}

echo "==> 结果目录 $OUTDIR"
echo "==> 目标 $BASE  并发阶梯: $CONCS  每档 ${DURATION}s"

# 真实店铺 id 清单：随机到不存在的 id 会让业务失败率虚高，掩盖真实性能。
SHOP_IDS="$OUTDIR/shop-ids.txt"
docker exec -i "${CONTAINER_MYSQL:-hmdp-mysql}" \
  sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B hmdp' 2>/dev/null \
  <<< "SELECT id FROM tb_shop ORDER BY id;" > "$SHOP_IDS"
echo "==> 店铺 id 清单 $(wc -l < "$SHOP_IDS") 条"

run_one() {
  local name=$1; shift
  local conc=$1; shift
  "$PY" "$HERE/loadgen.py" \
    --base "$BASE" --label "$name-c$conc" --concurrency "$conc" --duration "$DURATION" \
    --out "$OUTDIR/${name}-c${conc}.json" "$@"
}

# ---- 场景1：基线。分类列表是整表 Redis 缓存，理论上应该最快，用来标定机器上限 ----
for c in $CONCS; do
  run_one baseline "$c" --path '/shop-type/list' --expect-success
done

# ---- 场景2：店铺详情。走 穿透缓存（Redis miss 才回表），压 214 家店随机命中 ----
for c in $CONCS; do
  run_one shop-cache "$c" --path '/shop/{id}' --ids-file "$SHOP_IDS" --expect-success
done

# ---- 场景3：ES 搜索。带关键词+分类+排序，走完整 bool 查询与聚合 ----
for c in $CONCS; do
  run_one es-search "$c" --path '/shop/search?keyword=%E6%B5%B7%E5%BA%95%E6%8D%9E&current=1&pageSize=10' --expect-success
done

# ---- 场景4：秒杀受理。只压"受理"这一段，返回 orderId 即算成功 ----
# 秒杀会产生订单，必须明确指定隔离环境中的新测试券并主动启用。
if [ "${RUN_SECKILL:-0}" = 1 ]; then
  : "${TEST_VOUCHER_ID:?启用秒杀时必须指定独立测试券 TEST_VOUCHER_ID}"
  [ -f "$HERE/tokens.txt" ] || { echo "缺少测试令牌" >&2; exit 1; }
  run_one seckill "$(echo "$CONCS" | awk '{print $1}')" \
    --method POST --path "/voucher-order/seckill/$TEST_VOUCHER_ID" \
    --tokens "$HERE/tokens.txt" --expect-success
else
  echo "跳过秒杀场景：如需执行，请在隔离环境使用 RUN_SECKILL=1 并指定 TEST_VOUCHER_ID"
fi

echo "==> 全部完成，结果在 $OUTDIR"
ls -1 "$OUTDIR"
