-- Deduplication and every affected counter are updated atomically.
-- KEYS: event ledger, first counter hash, second counter hash
-- ARGV: event key, delta, first field, second field (empty to skip)
if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then return 0 end
local delta = tonumber(ARGV[2])
-- Lua runtime errors do not roll back earlier Redis writes. Validate every target first.
local function checkCounter(key, field)
  local kind = redis.call('TYPE', key).ok
  if kind ~= 'none' and kind ~= 'hash' then error('Counter must be a hash') end
  local value = redis.call('HGET', key, field)
  if value and (not string.match(value, '^%-?%d+$') or math.abs(tonumber(value)) > 9000000000000000) then
    error('Counter must contain a safe integer')
  end
end
checkCounter(KEYS[2], ARGV[3])
if ARGV[4] ~= '' then checkCounter(KEYS[3], ARGV[4]) end
redis.call('HINCRBY', KEYS[2], ARGV[3], delta)
if ARGV[4] ~= '' then redis.call('HINCRBY', KEYS[3], ARGV[4], delta) end
redis.call('HSET', KEYS[1], ARGV[1], '1')
return 1
