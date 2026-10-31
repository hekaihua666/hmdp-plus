# 部署与本地开发入口

项目提供两种运行方式，业务代码共用，中间件数据卷保留。

| 方式 | 后端在哪里运行 | 文档 |
| --- | --- | --- |
| 本地开发 | IDEA Run / Debug，Docker仅运行依赖与Nginx | [本地开发](../docs/LOCAL_DEVELOPMENT.md) |
| 完整部署 | backend与全部依赖由Docker运行 | [Docker部署](../docs/DEPLOYMENT.md) |

- `compose.local.yaml`：本地开发；用项目根目录 `start-local.bat` 切换。
- `compose.yaml`：完整部署；Linux云端可叠加 `cloud/compose.cloud.yaml`。
- `.env.example`：复制成 `.env` 后填写自己的密码；真实文件不提交。
- `prepare-ik.ps1` / `prepare-ik.sh`：获取与ES匹配的IK插件。
- `infra/mysql/init/00-schema.sql` / `02-demo.sql`：空数据卷首次初始化；不用于覆盖已有数据库。
- `init.ps1` / `cloud/init.sh`：准备Outbox结构、MQ Topic和ES Mapping。
- `cloud/reindex.sh`：受控维护时全量导入商户，不能作为每次启动操作。
- `loadtest/`：只读阶梯测试和秒杀测试说明，旧重置脚本已停用。

默认端口：网页8080、后端8081、MySQL3307、Redis6379、ES9200、NameServer9876、Broker10911/10909。

## 已有本机数据

已有Docker卷不会重新执行初始化SQL。切换运行模式不删除卷、不重置库存、不重建索引。原含账号和运行数据的整库快照不随公开版本分发。

## 账号

没有通用默认后台密码，按 [账号初始化](../docs/ACCOUNTS.md) 设置自己的管理员与商户账号。平台和商户页面分别是 `/platform/login.html`、`/merchant/login.html`。

历史的 `infra/README.md` 与 `rocketmq/README.md` 保留此前阶段背景，实际运行以本页链接的新版指南为准。
