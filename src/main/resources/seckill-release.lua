-- 幂等补偿（释放预占）。KEYS: stock / owners / marker；ARGV: userId / orderId / reservationState。
-- 返回码：0 完成；2 归属冲突；3 库存缺失；4 预占结果待核查。
-- 数据库先提交 CANCELLING；UNKNOWN/HELD 无凭据时保留补偿状态。
-- 只有 0 会让上层把申请置 FAILED 并清预占态；2/3/4 一律抛异常、事务回滚，状态留在 CANCELLING 等下一轮重试。

-- 取两条 Redis 凭据：本申请的状态标记（HELD/CLOSED），以及该用户当前的预占归属（userId -> orderId）。
-- 【大白话】先查两样东西——这单盖章了没、这份预占挂的是谁的订单。
local marker = redis.call('get', KEYS[3])
local owner = redis.call('hget', KEYS[2], ARGV[1])

-- 【已结案】上次补偿已经盖章 CLOSED，库存早已还过。
-- 【大白话】这单退过了，重复调用一律不再退第二次。
if marker == 'CLOSED' then
    -- 【异常】已结案但归属仍挂着本单，两条凭据自相矛盾。
    -- 【大白话】账面说关单了，东西却还挂我名下，别动，报上去查。
    if owner == ARGV[2] then
        return 2
    end
    return 0
end

-- 【正常退还】归属明确写着属于本次 orderId，是确凿凭据，可以安全退回库存。
-- 【大白话】确认就是我扣的那一份，还回去，顺手把自己的名字划掉。
if owner == ARGV[2] then
    -- 库存键不存在时 incr 会凭空创建值为 1 的 key，等于无中生有造出库存。
    -- 【大白话】库存账本都不在了，宁可报错也不能凭空变出一份库存。
    if redis.call('exists', KEYS[1]) == 0 then
        return 3
    end
    redis.call('incr', KEYS[1])
    redis.call('hdel', KEYS[2], ARGV[1])
elseif marker == 'HELD' then
    -- 【异常】标记说预占着，归属却不是本单（可能指向他人或已消失）。
    -- 【大白话】两个凭据打架，什么都别动，报上去查。
    return 2
elseif ARGV[3] ~= 'NONE' then
    -- 【待核查】两条 Redis 凭据都丢了，但数据库记录的预占态是 UNKNOWN/HELD，即可能真扣过库存。
    -- 缺失凭据不等于未扣过库存，不能封口或让上层结束补偿。
    -- 【大白话】看不清到底扣没扣，那就既不退也不结案，留着让人查。
    return 4
end

-- 走到这里只可能是两种情形：刚退还成功，或数据库明确记录从未预占（reservationState=NONE）。
-- 盖章 CLOSED，用于拦截之后迟到的预占调用（reserve 脚本见了 CLOSED 会直接拒绝）。
-- 【大白话】确认处理干净了，盖个章，以后谁再来都给我退回去。
redis.call('set', KEYS[3], 'CLOSED')
return 0
