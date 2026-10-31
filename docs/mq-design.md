# 秒杀订单RocketMQ接入设计

## 范围和选型

本次只迁移创建订单链路，不改8—12章、不增加支付/核销/退款、延迟关单或ES消息同步。
使用RocketMQ 5.3.2 Broker、rocketmq-spring-boot-starter 2.3.4（经典Remoting客户端5.3.2），保留Boot 2.7.4及Java 8编译目标。本机使用Java 17运行。

方案为**持久化申请记录 + 普通消息 + 租期补发 + 幂等消费**。申请表兼作本业务的待发布记录，不新建通用消息中台。
先提交申请再执行Redis预占，保证进程退出后有恢复入口。代价是每次受理增加数据库写入，不能宣称所有请求都由Redis挡住；本次不提供未经压测的性能数字。
事务消息可以作为后续对比方案，不同时实现两套生产机制；半消息回滚不会撤销Redis，也仍需持久裁决和补偿。

## 正常链路

1. 校验身份、券和活动时间；缺少Redis库存时，仅在没有处理中申请的情况下按数据库库存初始化，禁止覆盖待处理预占。
2. 生成orderId并提交INIT申请，随后在短MySQL事务中锁定申请行，执行Redis Lua幂等预占，将申请更新为READY。
3. 生产者按数据库条件更新领取10秒发送租期；发送相同eventId的普通消息，确认后记录发送时间。确认结果未知只安排补发，不直接退库存。
4. HTTP返回字符串orderId，表示已受理。浏览器查询本人申请，只有SUCCEEDED才显示抢购成功。
5. 消费者锁定同一申请行，核验消息身份/版本/状态和Redis预占凭据；数据库条件扣库存、插入订单、SUCCEEDED在一个MySQL事务提交，返回后SDK才确认消费。
6. 同orderId重复消息直接幂等返回；不同orderId命中相同用户/券属于业务冲突，进入本次申请的终止及补偿，不能当作本次成功。

## 数据与状态

`tb_seckill_request`主键为order_id。state和发送进度独立，不能将“发送成功”当“落单成功”。

```text
INIT -> READY -> SUCCEEDED
  |       |
  +-------+-> CANCELLING -> FAILED
```

- INIT：受理已持久化，Redis结果待确认。Lua返回未知时事务回滚，保留INIT供恢复。
- READY：已确认本次预占，允许发布和落单。last_sent_time不代表消费完成。
- CANCELLING：已提交终止裁决，禁止任何旧消息落单，正在释放本次预占。
- FAILED：补偿完成或确认无需补偿，申请失败。不能复活该申请；允许重试时使用新orderId。
- SUCCEEDED：订单已创建，保留本场购买资格；未接入支付，不能自动释放资格或宣称支付完成。

每条消息有固定的`CREATE_ORDER:<orderId>`事件标识、version=1、用户和券ID。补发沿用原身份；RocketMQ msgId不是业务幂等键。
订单表有主键与`(user_id,voucher_id)`唯一约束。Redisson用户锁继续保留以复用现有串行约束，正确性不只依赖锁；唯一冲突必须让本次库存扣减事务一起回滚。
应用启动时核对申请表及唯一索引，缺失时明确停止启动，避免未迁移数据库仍接受抢购。

## Redis键与补偿

| Key | 用途 |
| --- | --- |
| seckill:stock:{voucherId} | 可预占库存 |
| seckill:owners:{voucherId} | Hash：用户ID -> 本次预占orderId |
| seckill:request:{orderId} | HELD/CLOSED，重复预占与迟到执行的防线 |

预占使用seckill-reserve.lua，释放使用seckill-release.lua。释放前核对本次orderId；重复释放不增加库存。即使没有预占也写CLOSED，阻止迟到预占。
终止必须先在数据库提交CANCELLING，再执行Lua，最后持久化FAILED。消费者、预占与终止使用同一申请行锁；成功订单不能被后来的发送异常或死信撤销。
MySQL事务不包含Redis原子回滚，Redis脚本也不会自动回滚MySQL。当前使用单机Redis，脚本涉及多Key，未声明支持Redis Cluster跨槽执行。
键未设置短TTL，以免处理中和重放时丢失幂等依据；活动结束后的归档/清理须结合申请与购买资格统一设计，不随意删除。

## 故障窗口与处理

| 故障窗口 | 恢复规则 |
| --- | --- |
| 申请提交后、Lua执行前退出 | INIT可被恢复任务发现；在截止前继续预占，过期则受控终止 |
| Lua已成功、READY提交前退出 | INIT重试同orderId，HELD不再次扣库存；未知结果不等于失败 |
| READY提交后、发送前退出 | 恢复任务补发 |
| Broker已收、确认丢失或发送后退出 | 相同事件重发，消费者幂等；不因超时立刻退库存 |
| 数据库扣库存后插单失败 | 数据库事务回滚，消费者抛异常触发重试 |
| 事务已提交、消费确认前退出 | 重复消息读到SUCCEEDED，不再次扣库存 |
| 补偿Lua已完成、FAILED提交前退出 | CANCELLING再次释放，同orderId不会重复加库存 |
| 终止和迟到消息竞争 | 同一申请行锁裁决；CANCELLING/FAILED禁止落单 |
| Redis不可读或仅部分预占数据丢失 | 不猜测、不无条件加库存，保留待恢复状态；需核查库存/归属数据 |
| Redis库存完全丢失但有处理中申请 | 不直接用数据库库存重建，避免覆盖在途预占；先核查并收敛申请 |

恢复任务每秒分批扫描20条，失败延后10秒并轮转；发送有10秒租期，已发送未终态的申请默认60秒再核对补发。
申请默认处理期限600秒，超期是**显式终止本次申请的业务规则**，不是推断消息不存在。终止前锁定申请行，已成功则不终止；之后旧消息不能落单。

## 重试与死信

经典PushConsumer显式设置最多3次消费重试；该配置不是所有RocketMQ版本的固定默认值。临时故障抛异常，不catch后正常返回。
专用DLQ消费者校验消息与申请身份，再提交终止和幂等补偿。成功申请保持成功；身份不匹配的异常消息不擅自退款，保留异常处理路径，需要运维核查。
DLQ处理本身失败时会重试；即使该路径暂不可用，申请表中的CANCELLING仍由恢复任务继续处理。不能把“进死信”当作业务处理完成。

## 前后端契约

- POST /voucher-order/seckill/{voucherId}：成功返回字符串申请ID，表示受理，不表示订单已创建。
- GET /voucher-order/requests/{orderId}：本人申请状态，其他用户得到统一无权/不存在提示。
- GET /voucher-order/requests?shopId=...：本人本店最近10次申请，用于提交响应丢失、刷新或丢失ID时恢复查询。
- 前端有独立12秒提交超时；查询失败不显示抢购失败；自动轮询暂停后保留手动查询。申请ID全程按字符串保存，避免JS精度损失。
- 券库存展示仍由原券查询接口提供；已受理库存与最终数据库库存不要求时时相等。

## 部署与边界

详见deploy/rocketmq/README.md。Broker采用命名数据卷及SYNC_FLUSH，单节点单副本不是高可用集群。数据库、Redis和Broker各自的数据持久化仍是恢复前提；不承诺机器磁盘损坏后零数据丢失。
待确认/补偿中的申请需要监控，本机可通过申请表与orderId日志追踪。当前不开放匿名或通用“强制重放”HTTP接口。
8—12章、延迟关单、真实支付、ES Outbox是后续任务，本次不混入。

## 学习与面试讲解入口

按以下顺序阅读，不把SDK注解当作整个方案：

1. VoucherOrderServiceImpl：为何先持久化申请、返回ID为何只代表受理。
2. SeckillRequestService.create/prepare：申请行锁、未知结果与READY裁决。
3. SeckillReservationService与两个Lua：预占归属、重复执行和迟到请求的终止标记。
4. SeckillOrderPublisher：发送租期、结果未知、相同事件补发。
5. SeckillOrderConsumer与consume事务：幂等、数据库唯一约束、提交后确认。
6. SeckillRecovery与SeckillDeadLetterConsumer：重启恢复、超期终止、幂等补偿。
7. VoucherOrderController与shop-detail.html：本人查询、长ID精度、响应丢失后的最近申请入口。

可以准确描述为：“基于RocketMQ实现优惠券异步抢购下单，通过持久化申请、幂等预占与数据库唯一约束，支持发送补发、消费重试、死信补偿及处理结果查询，并完成故障恢复联调。”
不要写零丢失、跨系统强一致、Exactly-once消息交付或未经压测的吞吐量。消息允许重复，目标是订单与库存业务副作用不重复。
