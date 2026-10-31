#!/usr/bin/env bash
# 云服务器一键启动（Linux / Ubuntu 22.04）。
#
# 做两件事：① 确保 5 个数据卷存在（幂等）② 构建并启动全套服务。
# 前提：已装好 Docker + docker compose 插件，deploy/.env 里填好 DB_PASSWORD。
#
# 用法：bash deploy/cloud/start-all.sh

set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$DEPLOY_DIR"

echo ''
echo '== [0/2] 前置检查 =='

if ! command -v docker >/dev/null 2>&1; then
  echo '  [错误] 没装 Docker，请先运行：sudo bash deploy/cloud/setup-server.sh'
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo '  [错误] 缺少 docker compose 插件，请先运行：sudo bash deploy/cloud/setup-server.sh'
  exit 1
fi

if [ ! -f .env ]; then
  echo '  [错误] 缺少 deploy/.env，请先运行：sudo bash deploy/cloud/setup-server.sh'
  exit 1
fi
echo '  Docker 与配置就绪'

echo ''
echo '== [1/2] 确保数据卷存在（幂等，不删已有数据） =='
# 卷名与 compose.yaml 底部的 external 名一致；云上是全新机器必须要先建。
for v in hmdp-infra_mysql-data hmdp-infra_es-data hmdp-mq_broker-store hmdp-mq_broker-logs hmdp-mq_namesrv-logs; do
  docker volume create "$v" >/dev/null
  echo "  $v 就绪"
done

echo ''
echo '== [2/2] 构建并启动全套服务 =='
echo '  首次构建要拉 Maven 依赖和多个镜像，约 10-20 分钟，请耐心等待...'
# 叠加 cloud/compose.cloud.yaml：后端改用「Maven 走国内镜像」的 Dockerfile。
docker compose -f compose.yaml -f cloud/compose.cloud.yaml up -d --build

echo ''
echo '等待服务就绪...'
sleep 30
docker compose -f compose.yaml ps

echo ''
echo '启动完成。接下来执行初始化脚本：bash deploy/cloud/init.sh'
