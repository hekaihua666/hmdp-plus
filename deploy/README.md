# HMDP 部署说明（单一 Compose 项目）

所有中间件已合并成**一个 Compose 项目 `hmdp`**，一键启动。

## 一键启动 / 停止

在项目根目录双击：

| 脚本 | 作用 |
| --- | --- |
| `start-all.bat` | 启动 Docker Desktop → 确保数据卷存在 → 启动全部中间件 + 后端 |
| `stop-all.bat` | 停止全部容器（数据保留） |

启动前先确认 `deploy/.env` 已配置 `DB_PASSWORD`（与现有 MySQL 容器 root 密码一致）。
后端已随 Compose 一起容器化，**无需再在 IDEA 里运行**。

也可以在 Docker Desktop 里直接点项目 `hmdp` 的 ▶（前提是 Docker Desktop 已启动）。

### 首次部署：先跑初始化脚本

中间件起来后，还有三样「结构」需要建，否则后端起来会缺表/缺 Topic/缺索引：

```powershell
pwsh -File deploy/init.ps1
```

该脚本**幂等**，做三件事（详见下文「初始化脚本」节）：

1. 建 `tb_shop_sync_event` 表（并幂等补 `revision` 列）；
2. 建 4 个 RocketMQ Topic（秒杀 + 商户同步 + 两个死信队列）；
3. 建 ES 索引 `hmdp_shop`（含 IK 中文分词 mapping）。

## 服务与端口

| 服务 | 镜像 | 容器名 | 宿主机端口 |
| --- | --- | --- | --- |
| MySQL | `mysql:8.0` | `hmdp-mysql` | **3307** |
| Redis | `redis:6.2.4` | `hmdp-redis` | 6379 |
| Elasticsearch | `hmdp-elasticsearch:7.17.24-ik`（自建）| `hmdp-es` | 9200 |
| Nginx | `nginx:alpine` | `hmdp-nginx` | 8080 |
| RocketMQ NameServer | `apache/rocketmq:5.3.2` | `hmdp-namesrv` | 9876 |
| RocketMQ Broker | `apache/rocketmq:5.3.2` | `hmdp-broker` | 10911 / 10909 |
| RocketMQ 卷初始化 | `apache/rocketmq:5.3.2` | `hmdp-volume-init-1` | —（一次性，跑完即退出，正常）|
| 后端应用 | `hmdp-backend:1.0-SNAPSHOT`（自建）| `hmdp-backend` | 8081，容器内 |

### 为什么 MySQL 用 3307
本机 native `MySQL80` 是开机自启的 Windows 服务，占着 3306，且还在服务其他学习项目
（`sky_take_out`、`tlias`、`itheima` 等）。为避免互相影响，容器 MySQL 用 **3307**，
`src/main/resources/application.yaml` 已指向 3307。

## 数据卷（重要）

所有卷都是 **`external: true`**，指向「合并前那个时代创建的」卷名：

| Compose 卷名 | 实际卷名 | 装什么 |
| --- | --- | --- |
| `mysql-data` | `hmdp-infra_mysql-data` | MySQL 全部业务数据 |
| `es-data` | `hmdp-infra_es-data` | ES 索引 `hmdp_shop` |
| `broker-store` | `hmdp-mq_broker-store` | MQ 消息与消费进度 |
| `broker-logs` | `hmdp-mq_broker-logs` | Broker 日志 |
| `namesrv-logs` | `hmdp-mq_namesrv-logs` | NameServer 日志 |

**为什么用 `external: true`：**
1. 合并项目后仍复用同一批卷，**数据不迁移、不丢失**；
2. 避免 compose 每次都警告 `created for project "hmdp-infra" (expected "hmdp")`；
3. external 卷**不会被 `docker compose down -v` 删除**，更安全。

**注意**：external 卷必须已存在，否则 `up` 会报错。`start-all.bat` 已用
`docker volume create` 预先确保它们存在（该命令**幂等**，对已有卷是空操作，不动数据）。

## 绑定挂载（配置与运行时数据）

| 宿主机路径 | 容器内路径 | 用途 |
| --- | --- | --- |
| `deploy/infra/mysql/init/` | `/docker-entrypoint-initdb.d` | 首次启动导入的 SQL（只读）|
| `deploy/infra/redis/` | `/data` | Redis 的 `dump.rdb`（可读写）|
| `deploy/infra/nginx/nginx.conf` | `/etc/nginx/nginx.conf` | Nginx 配置（只读）|
| `src/main/resources/nginx-1.18.0/html` | `/usr/share/nginx/html` | 前端页面（只读）|
| `deploy/rocketmq/broker.conf` | `/home/rocketmq/broker.conf` | Broker 配置（只读）|

⚠️ 前端页面是**绑定挂载**项目里的目录，所以**不要删除** `src/main/resources/nginx-1.18.0/html`。

## 目录结构

```
deploy/
├── compose.yaml              ← 唯一的编排文件（MySQL/Redis/ES/Nginx/RocketMQ）
├── infra/
│   ├── mysql/init/*.sql      hmdp 全量数据（首次启动自动导入）
│   ├── redis/dump.rdb        Redis 快照
│   ├── elasticsearch/
│   │   ├── Dockerfile        基于 ES 7.17.24 追加 IK 插件
│   │   └── plugins/ik/       IK 中文分词器
│   ├── nginx/nginx.conf
│   └── README.md             （旧说明，已被本文件取代）
├── rocketmq/
│   ├── broker.conf
│   └── README.md             （旧说明，命令已改用 deploy/compose.yaml）
└── （原 infra/compose.yaml 与 rocketmq/compose.yaml 已删除，内容全部并入 compose.yaml）
```

> 合并前的两个旧 compose 文件已确认无用并删除；如需追溯，可从 Git 历史查看。

## Nginx 转发说明

`nginx.conf` 里后端地址是 `backend:8081`，因为后端已和 Nginx 同在一个 Compose 网络里，
容器间直接用服务名访问。

## 后端镜像与图片目录

后端容器 `backend` 通过项目根 `Dockerfile` 多阶段构建（Maven 打包 → JRE 运行），连接配置全部
从环境变量注入（见 `deploy/.env`）。图片上传目录由环境变量 `IMAGES_DIR` 指定为容器内
`/app/imgs`，该路径直接挂载项目里的前端图片目录
`src/main/resources/nginx-1.18.0/html/hmdp/imgs`，因此后端写图、Nginx 读图用的是同一份文件，
上传后的图片无需额外同步就能被前端访问。

> ⚠️ 不要把空目录挂到 `hmdp/imgs` 上：那一层已经由 `html` 整目录挂载覆盖了，再挂一个空目录会
> 把内置的图标、分类图、笔记封面全部遮住，页面表现为「logo 和图片全裂」。

## 初始化脚本

`deploy/init.ps1` 负责「结构初始化」，幂等可重复执行，前置条件是三个容器已运行：

| 步骤 | 内容 | 幂等依据 |
| --- | --- | --- |
| 1 | 建 `tb_shop_sync_event` 表 | `CREATE TABLE IF NOT EXISTS` |
| 1b | 补 `revision` 列 | 先 `SHOW COLUMNS LIKE 'revision'` 检查 |
| 2 | 建 4 个 RocketMQ Topic | `updateTopic` 对已存在 topic 是覆盖更新 |
| 3 | 建 ES 索引 `hmdp_shop` | 先 `HEAD /hmdp_shop` 检查 |

> 脚本里 mapping 与 `src/main/java/com/hmdp/constants/EsConstants.java` 的 `SHOP_INDEX_JSON` 保持一致；
> 若以后改了 Java 里的 mapping，记得同步改 `init.ps1`。

## 可选步骤：灌数据

结构初始化（`init.ps1`）只建「表 / Topic / 索引」的空架子，不含业务数据。业务数据分两类，按需灌：

| 数据 | 命令 | 说明 |
| --- | --- | --- |
| ES 全量索引 | `mvn "-Dtest=ShopIndexRebuildTest" "-Dhmdp.es.rebuild=true" test` | 把 MySQL 现有商户全量写进 `hmdp_shop` |
| 演示数据 | `py scripts\seed_demo_data.py --apply` | 新增 200 店 / 20 作者 / 50 笔记 + 优惠券 |

> 首次部署：先跑 `init.ps1`（结构）→ 再跑上面 ES 全量重建（数据）。演示数据仅学习用，不是必选项。

## 重建 ES 索引

`hmdp_shop` 索引可从 MySQL 完整重建（商户数据变动后、或新环境首次拉起 ES 时）：

```powershell
mvn "-Dtest=ShopIndexRebuildTest" "-Dhmdp.es.rebuild=true" test
```

该测试默认跳过，不会在普通 `mvn test` / `package` 中执行。

## 常用命令

```powershell
# 状态
docker compose -f deploy/compose.yaml ps

# 日志
docker compose -f deploy/compose.yaml logs --tail 50 broker
docker compose -f deploy/compose.yaml logs --tail 50 mysql

# 重启单个服务
docker compose -f deploy/compose.yaml restart redis

# 查看 MQ 消费进度
docker exec hmdp-broker sh mqadmin consumerProgress -n namesrv:9876 -g hmdp-seckill-order-consumer

# 手动创建 topic（broker.conf 里 autoCreateTopicEnable=false，新 topic 必须手动建）
# 已收进 deploy/init.ps1，下面这条仅作单条示例（秒杀主 topic）：
docker exec hmdp-broker sh mqadmin updateTopic -n namesrv:9876 -b broker:10911 -t hmdp-seckill-order -r 4 -w 4

# 停止全部（保留数据）
docker compose -f deploy/compose.yaml stop
```

## 边界与注意

- 本机为**单节点部署**，不是高可用集群；结论不适用于生产或性能评估。
- 数据卷是 external，`down -v` **不会**删除它们；要真正清数据必须手动 `docker volume rm`。
- `deploy/infra/redis/dump.rdb` 是业务数据，不要删除；容器会持续把快照写回该目录。
