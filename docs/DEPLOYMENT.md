# 完整 Docker 部署

## 环境

Docker Engine / Docker Desktop 与 Compose 插件；Linux 建议从能同时运行 Java、MySQL、Redis、ES、RocketMQ 的配置开始。项目已在 Ubuntu 22.04、4核8GiB ECS 上完成演示部署。端口 8080 为网页入口，中间件端口默认绑定回环地址。首次构建需要网络下载 Maven 依赖及基础镜像。

```bash
git clone https://github.com/hekaihua666/hmdp-plus.git
cd hmdp-plus
cp deploy/.env.example deploy/.env
# 编辑 deploy/.env，设置自己的 DB_PASSWORD。
bash deploy/prepare-ik.sh
```

IK 从 [上游发行站点](https://release.infinilabs.com/) 下载 7.17.24，与 Elasticsearch 保持一致。Linux 下载脚本需要 curl 和 unzip。Windows 可使用 `deploy/prepare-ik.ps1`。

## 启动依赖并初始化

从项目根目录执行：

```bash
for volume in hmdp-infra_mysql-data hmdp-infra_es-data hmdp-mq_broker-store hmdp-mq_broker-logs hmdp-mq_namesrv-logs; do
  docker volume create "$volume"
done

docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/cloud/compose.cloud.yaml up -d --build mysql redis elasticsearch volume-init namesrv broker

# 等依赖就绪，建立 Outbox/Topic/索引；无须先启动 Java 后端。
bash deploy/cloud/init.sh

# 为 Maven 维护入口准备不含凭据的实际配置。
cp src/main/resources/application.example.yaml src/main/resources/application.yaml

# 仅在首次初始化或受控维护时全量重建商户索引。
bash deploy/cloud/reindex.sh

# 然后启动整套应用。
docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/cloud/compose.cloud.yaml up -d --build
bash deploy/cloud/verify.sh
```

`reindex.sh` 会替换 `hmdp_shop`，应在商户写入与增量同步暂停时执行，不能作为每次启动步骤。重复启动只需 `docker compose ... up -d`。

Windows 本地完整部署：准备好 IK 和环境文件后用 `start-all.bat` 启动，首次初始化使用 `powershell -ExecutionPolicy Bypass -File deploy/init.ps1`。本地 IDEA 模式见 [本地开发](LOCAL_DEVELOPMENT.md)。

## 数据与账号

空 MySQL 数据卷自动执行 `00-schema.sql` 和 `02-demo.sql`：包含当前必需表、Outbox revision、订单唯一约束，以及214家门店、10种分类、54篇笔记和合成作者。没有原用户、密码哈希、订单或原会话。

演示作者的占位手机号不能作为真实短信登录账号；C端验证码是教学流程，没有接入真实短信服务。

商户和管理员不带公共默认密码。可在显式初始化时设置自己的密码，详见 [账号初始化](ACCOUNTS.md)。

图片保存在前端 `imgs` 目录，backend 与 Nginx 共享挂载；已有图片和新上传图重启后保留。当前单机上传方案，没有对象存储、自动审核或孤立文件清理。

## 运维

```bash
docker compose --env-file deploy/.env -f deploy/compose.yaml ps
docker compose --env-file deploy/.env -f deploy/compose.yaml logs --tail 100 backend
docker exec hmdp-broker sh mqadmin consumerProgress -n namesrv:9876 -g hmdp-seckill-order-consumer
```

按 orderId 关联秒杀申请、消息和订单，按 shopId + revision 查商户同步。不要把 Outbox 的 SENT 当作 ES 已完成同步。

external 卷由 Docker 外部管理；停止服务保留数据。新机器需要先创建卷。已有数据库升级应按实际结构执行增量迁移，不重导演示数据，不直接重放全部历史 ALTER。

公网部署只开放必要的网页入口，并配置适合自己的入口保护。当前演示为单 Broker、单 ES 节点，不包含生产高可用配置。
