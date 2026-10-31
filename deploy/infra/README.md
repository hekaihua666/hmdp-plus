# HMDP 中间件一键启动说明

> ⚠️ **本说明已被取代（保留作历史留档）。**
> MySQL/Redis/ES/Nginx 与 RocketMQ 已合并为**单一 Compose 项目 `hmdp`**：
> 编排文件是 `deploy/compose.yaml`，完整说明见 **`deploy/README.md`**。
> 本目录原有的 `compose.yaml` 已删除（内容并入 `deploy/compose.yaml`），**请勿再使用**。
> 下面提到的 `docker compose -f deploy/infra/compose.yaml ...` 命令均已失效，
> 请改用 `docker compose -f deploy/compose.yaml ...`。

把 MySQL、Redis、Elasticsearch、Nginx 搬进 Docker 后，配合已有的 RocketMQ，
实现"双击一个脚本启动全部依赖"。**后端应用仍在 IDEA 里运行**（便于改代码即时生效）。

## 一键启动

在项目根目录双击：

```
start-all.bat      # 依次启动 Docker Desktop → RocketMQ → MySQL/Redis/ES/Nginx
stop-all.bat       # 停止全部容器（数据保留）
```

启动完成后，在 IDEA 里运行 `HmDianPingApplication` 即可。

## 端口分配

| 组件 | 容器端口 | 宿主机端口 | 说明 |
| --- | --- | --- | --- |
| MySQL | 3306 | **3307** | 3306 被开机自启的 native MySQL80 占用 |
| Redis | 6379 | 6379 | |
| Elasticsearch | 9200 | 9200 | |
| Nginx | 80 | 8080 | 前端页面入口 |
| RocketMQ NameServer | 9876 | 9876 | 独立 compose |
| RocketMQ Broker | 10911 | 10911 | 独立 compose |
| 后端应用 | — | 8081 | 在 IDEA 里运行，不在容器中 |

## 为什么 MySQL 用 3307

本机 native `MySQL80` 是 Windows 服务、开机自启，占着 3306，且还在服务其他学习项目
（`sky_take_out`、`tlias`、`itheima` 等）。为避免影响它们，容器 MySQL 改用 **3307**，
`application.yaml` 中的数据源已指向 3307。两套 MySQL 互不干扰。

## 目录结构

```
deploy/infra/
├── compose.yaml                    # 中间件编排（MySQL/Redis/ES/Nginx）
├── mysql/init/01-hmdp.sql          # hmdp 全量数据，首次启动自动导入
├── redis/dump.rdb                  # Redis 数据快照，启动时自动加载
├── elasticsearch/
│   ├── Dockerfile                  # 基于 ES 7.17.24，追加 IK 插件
│   └── plugins/ik/                 # IK 中文分词器（含自定义词典）
└── nginx/nginx.conf                # 前端静态资源 + 请求转发配置
```

## 数据是怎么迁进来的

| 组件 | 迁移方式 |
| --- | --- |
| MySQL | `mysqldump` 全量导出（15 张表），首次启动由 `docker-entrypoint-initdb.d` 自动导入 |
| Redis | 直接挂载现有 `dump.rdb`（26 个 key，含秒杀库存、预占、GEO） |
| Elasticsearch | **不搬数据**，用 `ShopIndexRebuildTest` 从 MySQL 重建索引（214 家商户） |
| Nginx | 直接挂载项目里已有的前端目录，不复制 |

ES 之所以选择重建而非搬 data 目录：索引数据可从 MySQL 完整重建，比迁移二进制
data 目录更安全（避免版本/锁文件不一致）。

## Nginx 转发说明

`nginx.conf` 里后端地址用 `host.docker.internal:8081`（而不是 `127.0.0.1:8081`）。
因为 Nginx 在容器内，`127.0.0.1` 指向容器自身而非宿主机。若将来后端也进容器，
改成服务名（如 `backend:8081`）即可。

## 重建 ES 索引

商户数据变化后，或在新环境首次拉起 ES 时，需要显式重建：

```powershell
mvn -Dtest=ShopIndexRebuildTest -Dhmdp.es.rebuild=true test
```

该测试默认跳过，不会在普通 `mvn test` / `package` 中执行。

## 常用命令

```powershell
# 查看状态
docker compose -f deploy/infra/compose.yaml ps
docker compose -f deploy/rocketmq/compose.yaml ps

# 查看日志
docker compose -f deploy/infra/compose.yaml logs --tail 50 mysql

# 重启单个服务
docker compose -f deploy/infra/compose.yaml restart redis

# 彻底删除数据（谨慎：会清空 MySQL 与 ES）
docker compose -f deploy/infra/compose.yaml down -v
```

## 注意

- `deploy/infra/redis/dump.rdb` 是**业务数据**，不要删除；容器会持续把快照写回该目录。
- MySQL 数据存在命名卷 `hmdp-infra_mysql-data`，只有 `down -v` 才会清除。
- 本机部署为单节点，不是高可用集群；不适用于生产或性能结论。
