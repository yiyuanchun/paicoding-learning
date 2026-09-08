-- KEYS[1]: current user's daily action hash
-- KEYS[2]: daily activity ranking sorted set
-- KEYS[3]: monthly activity ranking sorted set
-- ARGV[1]: action field
-- ARGV[2]: user id (sorted-set member)
-- ARGV[3]: score delta
-- ARGV[4]: user action hash TTL, in seconds
-- ARGV[5]: daily ranking TTL, in seconds
-- ARGV[6]: monthly ranking TTL, in seconds

local currentScore = redis.call('HGET', KEYS[1], ARGV[1])
local scoreDelta = tonumber(ARGV[3])

if scoreDelta > 0 then
    if currentScore then
        return 0
    end

    redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
    redis.call('ZINCRBY', KEYS[2], scoreDelta, ARGV[2])
    redis.call('ZINCRBY', KEYS[3], scoreDelta, ARGV[2])

    if redis.call('TTL', KEYS[2]) < 0 then
        redis.call('EXPIRE', KEYS[2], tonumber(ARGV[5]))
    end
    if redis.call('TTL', KEYS[3]) < 0 then
        redis.call('EXPIRE', KEYS[3], tonumber(ARGV[6]))
    end
    return 1
end

if scoreDelta < 0 then
    if not currentScore or tonumber(currentScore) <= 0 then
        return 0
    end

    redis.call('HDEL', KEYS[1], ARGV[1])
    redis.call('ZINCRBY', KEYS[2], scoreDelta, ARGV[2])
    redis.call('ZINCRBY', KEYS[3], scoreDelta, ARGV[2])
    return 1
end

return 0
