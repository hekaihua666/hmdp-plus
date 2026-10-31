#!/usr/bin/env bash
# 把 MySQL 里的商户数据全量灌进 ES（重建 hmdp_shop 索引）。
#
# 为什么不在服务器上直接跑 mvn：服务器只负责跑容器，不需要装 JDK/Maven。
# 这里临时起一个 Maven 容器、接到 compose 网络里跑维护用测试，
# 跑完就删，不留垃圾。
#
# 用法：bash deploy/cloud/reindex.sh
# 注意：会先删后建 hmdp_shop 索引，只在学习环境用。

set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT_ROOT="$(cd "$DEPLOY_DIR/.." && pwd)"
cd "$DEPLOY_DIR"

if [ ! -f .env ]; then
  echo '  [错误] 缺少 deploy/.env'
  exit 1
fi
# shellcheck disable=SC1091
DB_PASSWORD="$(grep -E '^DB_PASSWORD=' .env | cut -d= -f2-)"

# compose 项目名固定为 hmdp（见 compose.yaml 的 name:），网络名就是 hmdp_default。
NETWORK="hmdp_default"
if ! docker network inspect "$NETWORK" >/dev/null 2>&1; then
  echo "  [错误] 找不到网络 $NETWORK，请先启动服务"
  exit 1
fi

echo ''
echo '== 全量重建 ES 索引 hmdp_shop =='
echo '  首次会下载 Maven 依赖，约 5-10 分钟...'

# M2_CACHE 让依赖跨次复用，第二次跑就会很快。
docker run --rm \
  --network "$NETWORK" \
  -v "$PROJECT_ROOT":/build \
  -v hmdp-maven-cache:/root/.m2 \
  -e DB_URL='jdbc:mysql://mysql:3306/hmdp?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true' \
  -e DB_USERNAME=root \
  -e DB_PASSWORD="$DB_PASSWORD" \
  -e REDIS_HOST=redis \
  -e REDIS_PORT=6379 \
  -e ES_HOST=elasticsearch \
  -e ES_PORT=9200 \
  -e ROCKETMQ_NAME_SERVER='namesrv:9876' \
  -w /build \
  maven:3.9-eclipse-temurin-17 \
  sh -c 'test -f src/main/resources/application.yaml || cp src/main/resources/application.example.yaml src/main/resources/application.yaml; mkdir -p /root/.m2 && cp maven-settings.xml /root/.m2/settings.xml; \
         mvn -B -Dtest=ShopIndexRebuildTest -Dhmdp.es.rebuild=true -DfailIfNoTests=false test'

echo ''
echo 'ES 重建完成。验证：curl http://localhost:9200/hmdp_shop/_count'
