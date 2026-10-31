# 商户 ES 同步：最小修复与验收

## 实现

保留现有 Outbox + RocketMQ 和 PENDING/SENT 两态；商户事务与 Outbox 同时提交，Redis 继续由提交后事件删除缓存。

1. Outbox 增加一个 BIGINT revision，每次商户写事务内 upsert 时递增。同一商户仍只有一行。
2. 领取、发送成功确认、发送失败退避都带 revision 条件。旧批次影响行数为 0 时不碰新批次，扫描任务继续发送新的 PENDING。
3. 消费者只按 shopId 触发同步。单条 LEFT JOIN 查询同时取得 MySQL 商户数据和该数据对应的 Outbox 版本；不能先读旧数据再单独读取新版本，也不能把消息版本套在新数据上。
4. ES 使用 external_gte：同版本可重试，旧版本写入返回 409 后确认消费，其他异常继续抛给 MQ 重试。
5. 死信使用单独的 consumeFailed SQL，不限定 PENDING，允许记录 SENT 的 last_error。仍保留 revision 条件，旧版本死信不覆盖新版本错误。死信不会自动重置为 PENDING。

版本从 1000000000001 开始，未产生过 Outbox 的商户联表结果使用 1000000000000；第一笔事件高于基线，且高于本项目旧索引的小整数内部版本。版本行必须保留，不能清空 Outbox 后继续沿用旧索引。全量重建是维护操作，执行时暂停商户写入及同步，避免与在线增量争写。

## 数据库升级

新环境按顺序执行：

1. database/migrations/20261030-shop-sync-outbox.sql
2. database/migrations/20261030-shop-sync-revision.sql（只执行一次）

本机 2026-10-30 已补齐 revision 列；升级前表结构及本轮修改前源码在 `_backups/shop-sync-minimal-20261030-144346`。

原 target/hmdp-1.0-SNAPSHOT.jar 被运行进程占用，普通 repackage 替换失败；已另外成功生成并检查可执行 target/hmdp-p4-fixed.jar。原 pom.xml 未改。

本轮没有重启现有后端。新代码需在下次启动或重启后生效；数据库版本列已兼容旧应用的 INSERT。

## 死信人工重放

核查并恢复 ES/MQ 后，针对已确认的商户及版本执行，例如将占位参数替换为实际数值：

```sql
UPDATE tb_shop_sync_event
SET state='PENDING',next_send_time=NOW(),last_error=NULL
WHERE shop_id=<商户ID> AND revision=<核查的版本>;
```

不递增版本，因为重放没有修改商户数据。MQ 发送成功只表示已到 Broker，不代表 ES 已同步；核查时读取 ES 文档或等待搜索刷新，不能只看 SENT。

## 验证结果（2026-10-30）

- `mvn -o -Dtest=ShopTransactionSyncTest,ShopSyncReliabilityTest test`：12 项定向测试；验证事务内 Outbox、Outbox 异常回滚、Redis 失效、消费异常传播、SENT 死信记录及真实 HLRC 版本参数/409 处理。
- `py scripts/verify_shop_sync_sql.py`：真实 MySQL 连接级临时表，9 项通过，涵盖事务回滚、领取、旧确认、旧发送失败、新记录保留、当前确认、SENT 死信记录、旧死信隔离、数据版本联表查询。临时表不修改业务记录。
- 真实 ES 独立临时索引：新版本成功、迟到旧版本 409、同版本重试成功；临时索引已删除。
- `mvn -o -Dtest=*,!SeckillSchedulingTest test`：100 项，94 项通过、6 项默认集成/维护测试跳过。
- 完整 `mvn -o test` 另发现原有 SeckillSchedulingTest 缺少 VoucherOrderMapper 测试 Bean，属于未修改的秒杀测试配置；本轮不扩大到秒杀模块。

这些记录包含 SQL 和 ES 协议验证；本轮没有操作账号密码、商户业务数据，也没有执行改店接口端到端联调。
