-- KEYS[1] : 限流键
-- ARGV[1] : 最大请求数
-- ARGV[2] : 时间窗（秒）
local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
end

if current > tonumber(ARGV[1]) then
    return 0
end

return 1
