-- KEYS: permanent version ledger, user's daily actions, day ranking, month ranking
-- ARGV: daily aggregate key, version, action field, user, desired score, TTL day, TTL month
local oldVersion = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')
local version = tonumber(ARGV[2])
if version <= oldVersion then return 0 end
local oldScore = tonumber(redis.call('HGET', KEYS[2], ARGV[3]) or '0')
local desired = tonumber(ARGV[5])
-- Validate ranking types before mutating the action hash.
for i = 3, 4 do
  local kind = redis.call('TYPE', KEYS[i]).ok
  if kind ~= 'none' and kind ~= 'zset' then error('Ranking must be a sorted set') end
end
local delta = desired - oldScore
if desired == 0 then redis.call('HDEL', KEYS[2], ARGV[3])
else redis.call('HSET', KEYS[2], ARGV[3], desired) end
if delta ~= 0 then
  redis.call('ZINCRBY', KEYS[3], delta, ARGV[4])
  redis.call('ZINCRBY', KEYS[4], delta, ARGV[4])
end
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[6]))
if redis.call('TTL', KEYS[3]) < 0 then redis.call('EXPIRE', KEYS[3], tonumber(ARGV[6])) end
if redis.call('TTL', KEYS[4]) < 0 then redis.call('EXPIRE', KEYS[4], tonumber(ARGV[7])) end
redis.call('HSET', KEYS[1], ARGV[1], version)
return 1
