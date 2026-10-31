-- 幂等预占（单机 Redis）。KEYS: stock / owners / marker。
-- ARGV: userId / orderId / begin / end / allowNew（1 仅用于刚提交预占意图的首次调用）。
-- 返回码：0 成功，1 售罄，2 重复，3 时间不符，4 已终止，5 库存缺失，6 重试凭据缺失。

-- 先看本申请自己的标记。HELD 说明这单已经预占过，重复调用直接算成功，保证幂等。
local marker = redis.call('get', KEYS[3])
if marker == 'HELD' then
    return 0
end
-- CLOSED 说明这单已经结案（要么退过库存要么终止过），再预占一律拒绝，防止迟到调用复活它。
if marker == 'CLOSED' then
    return 4
end
-- 查这个用户当前在 owners 里挂的 orderId，判断他是不是已经抢过这张券。
local owner = redis.call('hget', KEYS[2], ARGV[1])
if owner then
    -- 挂的正是本次 orderId：说明预占已经成功，只是标记丢了，补一个 HELD 再返回成功即可。
    if owner == ARGV[2] then
        redis.call('set', KEYS[3], 'HELD')
        return 0
    end
    -- 挂的是别人的 orderId：首次调用说明是重复下单；重试则说明凭据对不上，报 6 等核查。
    if ARGV[5] ~= '1' then
        return 6
    end
    return 2
end
-- UNKNOWN 的重试只能确认已有结果，不能在凭据丢失后再次扣库存或改判为“未预占”，所以非首次一律返回 6。
if ARGV[5] ~= '1' then
    return 6
end
-- 取 Redis 服务器时间统一判断活动窗口，避免多台应用服务器时钟漂移。
local now = tonumber(redis.call('time')[1])
if now < tonumber(ARGV[3]) or now > tonumber(ARGV[4]) then
    return 3
end
-- nil 是键不存在（系统异常），和值为 "0" 的售罄是两回事，这里先区分开。
local stock = redis.call('get', KEYS[1])
if not stock then
    return 5
end
if tonumber(stock) <= 0 then
    return 1
end
-- 三件事一次做完，靠 Lua 的原子性保证不被打断：扣库存、记归属、盖 HELD 章。
redis.call('decr', KEYS[1])
redis.call('hset', KEYS[2], ARGV[1], ARGV[2])
redis.call('set', KEYS[3], 'HELD')
return 0
