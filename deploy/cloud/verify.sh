#!/usr/bin/env bash
# 部署后冒烟验证：逐项检查「服务起来了、数据通了、接口能访问」。
#
# 用法：bash deploy/cloud/verify.sh
# 全部通过输出 PASS，有失败项列出具体原因并以非 0 退出。

set -uo pipefail

PASS=0
FAIL=0

check() {
  local name="$1"
  local cmd="$2"
  if eval "$cmd" >/dev/null 2>&1; then
    echo "  [PASS] $name"
    PASS=$((PASS + 1))
  else
    echo "  [FAIL] $name"
    FAIL=$((FAIL + 1))
  fi
}

# 把 SQL 从 stdin 送进容器执行。
# 密码必须写成容器的 $MYSQL_ROOT_PASSWORD（单引号里，由容器内 sh 展开），
# 不能在宿主机这层展开——宿主机没有这个变量，会因 set -u 直接终止脚本。
mysql_pipe() {
  docker exec -i hmdp-mysql sh -c \
    'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -N -B hmdp'
}

echo ''
echo '== 1. 容器状态 =='
for c in hmdp-mysql hmdp-redis hmdp-es hmdp-namesrv hmdp-broker hmdp-nginx hmdp-backend; do
  check "$c 运行中" "[ \"\$(docker inspect -f '{{.State.Running}}' $c 2>/dev/null)\" = 'true' ]"
done

echo ''
echo '== 2. 中间件连通性 =='
check 'MySQL 可登录' "echo 'select 1' | mysql_pipe"
check 'Redis 可 PING' "docker exec hmdp-redis redis-cli ping | grep -q PONG"
check 'ES 健康' "curl -fsS http://localhost:9200/_cluster/health | grep -qE '\"status\":\"(green|yellow)\"'"
# namesrv 镜像里没有 netstat/ss，改用 broker 上的 mqadmin 去连一次（能连上就说明在服务）。
check 'RocketMQ namesrv 可连通' "docker exec hmdp-broker sh -c 'sh /home/rocketmq/rocketmq-5.3.2/bin/mqadmin clusterList -n namesrv:9876'"

echo ''
echo '== 3. 数据已灌入 =='
check 'tb_shop 有数据' "echo 'select count(*) from tb_shop' | mysql_pipe | grep -qE '^[1-9]'"
check 'tb_shop_sync_event 表存在' "echo 'desc tb_shop_sync_event' | mysql_pipe"
check 'ES 索引有文档' "curl -fsS http://localhost:9200/hmdp_shop/_count | grep -qE '\"count\":[1-9]'"

echo ''
echo '== 4. 接口可访问 =='
check '后端直连 /shop-type/list' "curl -fsS http://localhost:8081/shop-type/list"
check 'Nginx 前端首页' "curl -fsS http://localhost:8080/ | grep -q html"
check 'Nginx 转发 /api/shop-type/list' "curl -fsS http://localhost:8080/api/shop-type/list"
check 'ES 搜索接口' "curl -fsS 'http://localhost:8081/shop/search?keyword=%E6%B5%B7%E5%BA%95%E6%8D%9E&page=1&size=5'"

echo ''
echo '== 5. 对外端口（从公网访问检查） =='
PUBLIC_IP="$(curl -fsS --max-time 5 https://ifconfig.me 2>/dev/null || echo '')"
if [ -n "$PUBLIC_IP" ]; then
  echo "  公网 IP: $PUBLIC_IP"
  echo "  浏览器打开：http://$PUBLIC_IP:8080/"
else
  echo "  [跳过] 取不到公网 IP"
fi

echo ''
echo "结果：$PASS 项通过，$FAIL 项失败"
if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
