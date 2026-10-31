#!/usr/bin/env bash
# HMDP 一键初始化脚本（Linux 版，幂等，可反复执行）。
#
# 负责「结构初始化」三件事：建表、建 RocketMQ Topic、建 ES 索引。
# 前提：先运行 start-all.sh 把 MySQL / Broker / ES 容器都拉起来。
#
# 用法：bash deploy/cloud/init.sh

set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT_ROOT="$(cd "$DEPLOY_DIR/.." && pwd)"
MIGRATION_DIR="$PROJECT_ROOT/database/migrations"

# 执行「管道进容器的 MySQL」：成功返回 stdout，失败直接退出。
mysql_pipe() {
  docker exec -i hmdp-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 hmdp'
}

# 轮询等待某个命令成功，最多等 $2 秒。
wait_for() {
  local desc="$1" secs="$2"
  shift 2
  local i=0
  while [ "$i" -lt "$secs" ]; do
    if "$@" >/dev/null 2>&1; then
      echo "  $desc 已就绪（${i}s）"
      return 0
    fi
    sleep 2
    i=$((i + 2))
    if [ $((i % 10)) -eq 0 ]; then echo "  等待 $desc ... ${i}s"; fi
  done
  echo "  [错误] 等待 $desc 超时（${secs}s）"
  return 1
}

echo ''
echo '== [0/3] 前置检查与等待就绪 =='
for c in hmdp-mysql hmdp-broker hmdp-es; do
  state="$(docker inspect -f '{{.State.Running}}' "$c" 2>/dev/null || echo false)"
  if [ "$state" != "true" ]; then
    echo "  [错误] 容器 $c 未运行，请先运行 start-all.sh"
    exit 1
  fi
done
echo '  容器都在运行'

# 首次启动 MySQL 要执行 01-hmdp.sql（258KB，214 家店），ES 启动也要 30-60 秒，
# 这里等到真正可用再继续，避免脚本跑一半失败。
wait_for 'MySQL' 180 docker exec hmdp-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "select 1"' || exit 1
wait_for 'Elasticsearch' 180 curl -fsS http://localhost:9200/_cluster/health || exit 1
wait_for 'RocketMQ namesrv' 120 docker exec hmdp-broker sh -c 'sh /home/rocketmq/rocketmq-5.3.2/bin/mqadmin clusterList -n namesrv:9876' || exit 1

echo ''
echo '== [1/3] 建表（幂等） =='
# 1a. 建 tb_shop_sync_event 表（CREATE TABLE IF NOT EXISTS，天然幂等）
mysql_pipe < "$MIGRATION_DIR/20261030-shop-sync-outbox.sql"
echo '  tb_shop_sync_event 表已就绪'

# 1b. 幂等补 revision 列：兼容「表已存在但缺列」的中间态
if echo "SHOW COLUMNS FROM tb_shop_sync_event LIKE 'revision';" | mysql_pipe | grep -q revision; then
  echo '  revision 列已存在，跳过'
else
  mysql_pipe < "$MIGRATION_DIR/20261030-shop-sync-revision.sql"
  echo '  revision 列已补齐'
fi

echo ''
echo '== [2/3] 建 RocketMQ Topic（幂等） =='
# broker.conf 里 autoCreateTopicEnable=false，新 Topic 必须手动建。
# %DLQ% 开头的死信队列必须存在，否则消费重试耗尽后投递失败。
topics=(
  'hmdp-seckill-order 4 4'
  '%DLQ%hmdp-seckill-order-consumer 1 1'
  'hmdp-shop-sync 4 4'
  '%DLQ%hmdp-shop-sync-consumer 1 1'
)
for spec in "${topics[@]}"; do
  read -r name r w <<< "$spec"
  if docker exec hmdp-broker sh mqadmin updateTopic -n namesrv:9876 -b broker:10911 -t "$name" -r "$r" -w "$w" >/dev/null 2>&1; then
    echo "  $name 已就绪"
  else
    echo "  [错误] 建 Topic $name 失败"
    exit 1
  fi
done

echo ''
echo '== [3/3] 建 ES 索引（幂等） =='
ES_URL='http://localhost:9200'
INDEX='hmdp_shop'
# Mapping 与 src/main/java/com/hmdp/constants/EsConstants.java 的 SHOP_INDEX_JSON 保持一致。
MAPPING='{
  "mappings": {
    "properties": {
      "id": { "type": "keyword" },
      "name": { "type": "text", "analyzer": "ik_max_word" },
      "namePinyin": { "type": "keyword" },
      "nameInitials": { "type": "keyword" },
      "typeId": { "type": "long" },
      "area": { "type": "text", "analyzer": "ik_max_word" },
      "address": { "type": "text", "analyzer": "ik_max_word" },
      "avgPrice": { "type": "long" },
      "score": { "type": "integer" },
      "sold": { "type": "integer" },
      "comments": { "type": "integer" },
      "images": { "type": "keyword", "index": false },
      "location": { "type": "geo_point" },
      "suggestion": { "type": "completion", "analyzer": "keyword" }
    }
  }
}'

status="$(curl -s -o /dev/null -w '%{http_code}' "$ES_URL/$INDEX")"
if [ "$status" = "200" ]; then
  echo "  索引 $INDEX 已存在，跳过"
else
  curl -fsS -XPUT "$ES_URL/$INDEX" -H 'Content-Type: application/json' -d "$MAPPING" >/dev/null
  echo "  索引 $INDEX 已创建"
fi

echo ''
echo '初始化完成：表 / Topic / ES 索引全部就绪。'
echo '下一步：bash deploy/cloud/reindex.sh 把 MySQL 商户数据全量灌进 ES。'
