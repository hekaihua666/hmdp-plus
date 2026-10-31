# 压测工具（deploy/loadtest）

只用 Python 标准库 + bash，服务器上不需要装 JMeter/k6/ab 任何东西。

## 如何理解测试环境

云服务器回环压测可以减少公网影响，但压测进程与后端共享 CPU、内存和网络资源。记录机器配置和资源占用；正式报告优先使用独立压测机，并重复测量。

## 四个场景

| 场景 | 接口 | 考察什么 |
| --- | --- | --- |
| 基线 baseline | `GET /shop-type/list` | 整表 Redis 缓存的读，机器上限参考线 |
| 缓存读 shop-cache | `GET /shop/{id}` | 穿透缓存命中路径，随机 214 家店 |
| ES 搜索 es-search | `GET /shop/search?keyword=海底捞` | bool 查询 + 排序 + 聚合，最重的一路 |
| 秒杀 seckill | `POST /voucher-order/seckill/{id}` | 受理吞吐（返回 orderId 即成功） |

## 快速开始

```bash
cd /opt/hmdp/deploy/loadtest
# 默认只运行三个只读场景；保存实际并发和完整结果。
CONCS="10 50 100 200" DURATION=60 bash run-all.sh
python3 summary.py /opt/hmdp/target/loadtest/<时间戳>
```

秒杀会创建申请和订单：只在隔离环境准备新测试券和测试账号，并显式设置 `RUN_SECKILL=1 TEST_VOUCHER_ID=<测试券ID>`。旧的 `reset-seckill.sh` 已停用，不要使用真实券做重置。

## 单独压一个场景

```bash
# 缓存读，50 并发跑 2 分钟，只随机真实存在的店铺 id
docker exec -i hmdp-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B hmdp' \
  <<< "SELECT id FROM tb_shop ORDER BY id;" > shop-ids.txt
python3 loadgen.py --path '/shop/{id}' --ids-file shop-ids.txt \
  --concurrency 50 --duration 120 --expect-success --label shop-cache-c50

# 秒杀：仅限隔离环境，先设置 TEST_VOUCHER_ID 和测试令牌
python3 loadgen.py --path "/voucher-order/seckill/$TEST_VOUCHER_ID" --method POST \
  --tokens tokens.txt --concurrency 50 --duration 60 --expect-success --label seckill-c50
```

## 秒杀结果如何看

每轮使用隔离环境中的新测试券，记录初始库存、测试用户和券 ID；等待全部申请进入终态，再核对申请、订单和库存。受理成功不等于最终落单成功，应分别记录两者。旧重置脚本会误删其他券的申请标记并遗漏在途消息，现已停用。

完整响应吞吐和延迟只计算收到完整 HTTP 响应的请求；传输异常单独计数，可能是连接失败，也可能是请求发出后超时。旧版 `notSent` 字段不能直接解释为未发送。

## 参数说明（loadgen.py）

- `--concurrency N`：总并发，会平均分到多个进程上（实际并发为进程数乘每进程线程数，可能因整除小于目标）
- `--processes N`：进程数，默认 CPU 核数（最多 8）
- `--duration N`：持续秒数
- `--expect-success`：额外解析响应 JSON 的 `success` 字段。后端业务失败也回 HTTP 200，
  只有开这个才分得清"真的成功"和"被拒了"
- `--out path.json`：结果写文件，方便 `summary.py` 汇总
- `--label name`：场景名，汇总时按去掉末尾 `-c<并发>` 的部分分组

## 创建独立秒杀测试数据

专用测试环境可执行 `python3 prepare-fixture.py --apply`，创建一张不关联现有门店的测试券（库存20）和50名新用户。脚本输出券ID与私有令牌文件位置；使用该文件进行一次性请求，之后核对申请终态、订单数与两边库存。不会重置旧券。此小批次用于正确性验收，不作为持续吞吐结果。
