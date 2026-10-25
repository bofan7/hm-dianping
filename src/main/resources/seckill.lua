-- 1. 参数
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3] -- 新增订单ID

-- 2. 数据key
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 3. 业务逻辑
-- 3.1. 判断库存
if(tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end
-- 3.2. 判断是否重复下单
if(redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 3.3. 扣库存
redis.call('incrby', stockKey, -1)
-- 3.4. 保存用户
redis.call('sadd', orderKey, userId)

-- 3.5. 核心：发送消息到 Stream 队列
-- XADD stream.orders * userId userId  voucherId id orderId
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)

return 0