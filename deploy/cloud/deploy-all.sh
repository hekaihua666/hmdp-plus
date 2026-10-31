#!/usr/bin/env bash
# 云服务器全套部署引导（一条命令走完全流程）。
#
# 依次执行：环境准备 → 启动服务 → 结构初始化 → ES 灌数据 → 冒烟验证。
# 每一步都是幂等的，中断后重跑不会出问题。
#
# 用法（在项目根目录）：
#   sudo bash deploy/cloud/deploy-all.sh
#
# 如果只是想重跑某一步，直接调用对应的子脚本即可。

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CLOUD_DIR="$PROJECT_ROOT/deploy/cloud"

step() {
  echo ''
  echo "###############################################################"
  echo "# $1"
  echo "###############################################################"
}

step '第 1 步：环境准备（装 Docker / 配镜像加速 / 生成 .env，可跳过已完成的）'
bash "$CLOUD_DIR/setup-server.sh"

step '第 2 步：启动全套服务（首次要构建镜像，10-20 分钟）'
bash "$CLOUD_DIR/start-all.sh"

step '第 3 步：结构初始化（表 / RocketMQ Topic / ES 索引）'
bash "$CLOUD_DIR/init.sh"

step '第 4 步：把 MySQL 商户数据全量灌进 ES（首次要下 Maven 依赖，5-10 分钟）'
bash "$CLOUD_DIR/reindex.sh"

step '第 5 步：冒烟验证'
bash "$CLOUD_DIR/verify.sh"

echo ''
echo '==============================================================='
echo ' 部署完成！'
PUBLIC_IP="$(curl -fsS --max-time 5 https://ifconfig.me 2>/dev/null || echo '<你的公网IP>')"
echo " 前端入口：http://$PUBLIC_IP:8080/"
echo " 后端直连：http://$PUBLIC_IP:8081/"
echo ''
echo ' 商户后台：/merchant/login.html'
echo ' 平台后台：/platform/login.html'
echo '==============================================================='
