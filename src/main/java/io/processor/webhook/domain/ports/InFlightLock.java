package io.processor.webhook.domain.ports;

import java.time.Duration;

public interface InFlightLock {
  /**
   * Attempts to acquire a fast-path lock (e.g., Redis SETNX). Returns true if lock acquired, false
   * if already locked.
   */
  boolean acquire(String key, Duration ttl);

  void release(String key);
}
