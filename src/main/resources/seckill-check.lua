-- 只读预检过滤明显售罄与重复请求，减少无效申请写入 MySQL。 ← 定位：只是过滤器
-- KEYS: stock / owners；ARGV: userId / begin / end。
-- 此处不扣库存；正式预占仍须在持久化受理后重新检查全部条件。 ← 边界：不算正式预占
-- 返回码：0 可受理 / 1 售罄 / 2 重复 / 3 不在活动时间 / 5 库存键缺失（系统异常）。

-- 取 Redis 服务器时间而非客户端时间戳，避免多台应用服务器时钟漂移导致时间窗判定不一致。
local now = tonumber(redis.call('time')[1])
if now < tonumber(ARGV[2]) or now > tonumber(ARGV[3]) then
    return 3   --不在活动时间内
end

-- owners 的键是 userId 存在与否，值是本单 orderId；hexists 只问“这个人抢过没”，不关心是哪一单。
if redis.call('hexists', KEYS[2], ARGV[1]) == 1 then
    return 2  --查是否重复下单
end

-- nil 表示键不存在（数据丢失/未初始化），与值为 "0" 的售罄是两回事：前者是系统异常，后者是业务结果。
local stock = redis.call('get', KEYS[1])
if not stock then
    return 5  --库存不存在
end
if tonumber(stock) <= 0 then
    return 1  --库存不足
end
return 0
