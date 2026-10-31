#!/usr/bin/env bash
# 云服务器首次环境准备（Ubuntu 22.04，只需跑一次）。
#
# 做四件事：① 装 Docker ② 配镜像加速 ③ 装 docker compose 插件 ④ 建 deploy/.env。
# 用法（在项目根目录）：sudo bash deploy/cloud/setup-server.sh
#
# 如果已经装了 Docker，本脚本会跳过安装，只补镜像加速和 .env。
#
# 安装 Docker 会先试官方脚本，失败（国内经常被 reset）则自动改用阿里云镜像源。

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEPLOY_DIR="$PROJECT_ROOT/deploy"

echo ''
echo '== [1/4] 安装 Docker =='
if command -v docker >/dev/null 2>&1; then
  echo "  已安装：$(docker --version)，跳过"
else
  # 官方一键脚本：国外直连最省事，但国内网络经常被 reset。
  install_from_official() {
    curl -fsSL --connect-timeout 10 --max-time 90 https://get.docker.com -o /tmp/get-docker.sh
    sh /tmp/get-docker.sh
  }

  # 阿里云镜像源：Ubuntu 22.04 上最稳的方式，直接装 docker-ce 全家桶。
  install_from_aliyun() {
    apt-get update -qq
    apt-get install -y -qq ca-certificates curl gnupg
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL --connect-timeout 10 --max-time 90 \
      https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg \
      | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
    printf 'deb [arch=%s signed-by=/etc/apt/keyrings/docker.gpg] https://mirrors.aliyun.com/docker-ce/linux/ubuntu %s stable\n' \
      "$(dpkg --print-architecture)" \
      "$(. /etc/os-release && echo "$VERSION_CODENAME")" \
      > /etc/apt/sources.list.d/docker.list
    apt-get update -qq
    apt-get install -y -qq \
      docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  }

  echo '  先试官方脚本...'
  if install_from_official; then
    echo '  官方脚本安装成功'
  else
    echo '  官方脚本失败（网络被重置），改用阿里云镜像源安装...'
    install_from_aliyun
  fi
  systemctl enable --now docker
  echo '  Docker 安装完成'
fi

echo ''
echo '== [2/4] 配置镜像加速 =='
# 云服务器拉 docker.io 官方镜像很慢，配镜像源后快很多。
mkdir -p /etc/docker
if [ -f /etc/docker/daemon.json ]; then
  cp /etc/docker/daemon.json "/etc/docker/daemon.json.bak.$(date +%s)"
  echo '  已有 daemon.json，已备份'
fi
cp "$DEPLOY_DIR/cloud/daemon.json" /etc/docker/daemon.json
systemctl restart docker
echo '  镜像加速已配置'

echo ''
echo '== [3/4] 检查 docker compose =='
if docker compose version >/dev/null 2>&1; then
  echo "  $(docker compose version)"
else
  echo '  [错误] 缺少 compose 插件，尝试单独安装'
  apt-get update -qq && apt-get install -y docker-compose-plugin
fi

echo ''
echo '== [4/4] 准备 deploy/.env =='
if [ -f "$DEPLOY_DIR/.env" ]; then
  echo '  deploy/.env 已存在，跳过'
else
  # 生成一个随机密码，避免用弱密码；也方便你不想改就直接用。
  DB_PWD="$(head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 20)"
  printf 'DB_PASSWORD=%s\n' "$DB_PWD" > "$DEPLOY_DIR/.env"
  chmod 600 "$DEPLOY_DIR/.env"
  echo "  已生成 deploy/.env，数据库密码：$DB_PWD"
  echo '  （记下来，后面可以改成自己的密码）'
fi

echo ''
echo '环境准备完成。下一步：bash deploy/cloud/start-all.sh'
