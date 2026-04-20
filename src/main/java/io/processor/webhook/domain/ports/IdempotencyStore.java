package io.processor.webhook.domain.ports;

import io.processor.webhook.domain.model.ProcessedEvent;
import java.util.Optional;

public interface IdempotencyStore {
  /**
   * Attempts to acquire a database-level advisory lock. Returns true if acquired, false if held by
   * another transaction.
   */
  boolean acquireAdvisoryLock(String key);

  Optional<ProcessedEvent> findByKey(String idempotencyKey);

  void save(ProcessedEvent event);
}
