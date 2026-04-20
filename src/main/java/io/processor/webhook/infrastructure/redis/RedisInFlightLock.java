package io.processor.webhook.infrastructure.redis;

import io.processor.webhook.domain.ports.InFlightLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisInFlightLock implements InFlightLock {

  private static final String LOCK_PREFIX = "webhook:lock:";
  private static final String LOCK_VALUE = "IN_PROGRESS";
  private final StringRedisTemplate redisTemplate;

  @Override
  public boolean acquire(String key, java.time.Duration ttl) {
    String redisKey = LOCK_PREFIX + key;
    try {
      // setIfAbsent is the Spring Data Redis equivalent of the atomic SETNX command
      Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, LOCK_VALUE, ttl);
      return Boolean.TRUE.equals(acquired);
    } catch (Exception e) {
      // "Fail Open"
      // If Redis crashes or drops connection, we DO NOT want to reject the webhook.
      // We log the error and return TRUE (pretend we got the lock).
      // Why? Because our Postgres Advisory Lock is our ultimate safety net.
      // If Redis is down, we degrade gracefully by leaning entirely on the database.
      log.error("Error acquiring Redis lock for key: {}", key, e);
      return false;
    }
  }

  @Override
  public void release(String key) {
    String redisKey = LOCK_PREFIX + key;
    try {
      redisTemplate.delete(redisKey);
    } catch (Exception e) {
      // If we fail to delete the key, it's not a disaster.
      // We set a TTL during acquire(), so Redis will automatically clean it up eventually.
      log.warn("Failed to release Redis lock for key: {}. It will expire naturally.", key, e);
    }
  }
}
