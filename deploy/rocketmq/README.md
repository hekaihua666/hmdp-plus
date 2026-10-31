# HMDP本机RocketMQ

> ⚠️ **本目录的 compose 已合并（保留作历史留档）。**
> RocketMQ 与 MySQL/Redis/ES/Nginx 已合并为**单一 Compose 项目 `hmdp`**：
> 编排文件是 `deploy/compose.yaml`，完整说明见 **`deploy/README.md`**。
> 本目录原有的 `compose.yaml` 已删除（内容并入 `deploy/compose.yaml`），**请勿再使用**。
> 下文所有 `-f deploy/rocketmq/compose.yaml` 请改为 `-f deploy/compose.yaml`；
> 容器名也从 `hmdp-mq-broker-1` / `hmdp-mq-namesrv-1` 变为 `hmdp-broker` / `hmdp-namesrv`。
> `broker.conf` 仍在本目录，由 `deploy/compose.yaml` 以 `./rocketmq/broker.conf` 引用。

固定Broker 5.3.2、Spring starter 2.3.4、经典客户端5.3.2。仅使用NameServer和Broker，不启用gRPC Proxy，不占用网页8080和后端8081。

从项目根目录执行：

```powershell
docker compose -f deploy/rocketmq/compose.yaml up -d
docker compose -f deploy/rocketmq/compose.yaml exec -T broker sh mqadmin updateTopic -n namesrv:9876 -b 127.0.0.1:10911 -t hmdp-seckill-order -r 4 -w 4
docker compose -f deploy/rocketmq/compose.yaml exec -T broker sh mqadmin updateTopic -n namesrv:9876 -b 127.0.0.1:10911 -t '%DLQ%hmdp-seckill-order-consumer' -r 1 -w 1
```

首次volume-init只为专用命名卷赋予镜像用户3000写权限；没有删除数据。Broker-store保留消息及消费进度，不要用`down -v`清理运行数据。

数据库迁移必须显式执行，使用安装了pymysql、PyYAML、redis的Python环境：

```powershell
# 先只读检查旧Stream、重复订单及唯一约束。
python scripts/setup_seckill_mq.py
# 明确执行；快照默认位于Git忽略的target/mq-migration，也可用--backup-dir指定项目外目录。
python scripts/setup_seckill_mq.py --apply
```

若旧Stream有pending或未投递消息，脚本拒绝切换，必须先在旧链路核查处理；不能清空stream或直接忽略。MySQL DDL隐式提交，脚本先备份现有结构与库存，不谎称DDL全部可事务回滚。

然后运行`mvn test`，停止已核对的旧HMDP进程后再`mvn package`及启动。不要同时执行多个Maven构建或启动两个8081后端。
本机默认NameServer为127.0.0.1:9876；可用ROCKETMQ_NAME_SERVER环境变量覆盖。

网页入口为http://localhost:8080/index.html；商户在/merchant/login.html发布有效秒杀券，消费者在门店详情抢购并查询状态。无真实支付、核销或发短信服务。

## 常用定位

```powershell
docker compose -f deploy/rocketmq/compose.yaml ps
docker compose -f deploy/rocketmq/compose.yaml logs --tail 30 broker
docker compose -f deploy/rocketmq/compose.yaml exec -T broker sh mqadmin consumerProgress -n namesrv:9876 -g hmdp-seckill-order-consumer
```

按orderId关联申请状态、消息Key、后端日志和订单。发送超时先查申请，不直接退库存；处理超时是申请终止策略，默认600秒，可用SECKILL_REQUEST_TIMEOUT_SECONDS配置。
停止Broker会保留数据：`docker compose -f deploy/rocketmq/compose.yaml stop broker`；恢复使用`start broker`。
单Broker本机部署没有HA保障，不把联调验证表述成生产可用性或高并发性能报告。
