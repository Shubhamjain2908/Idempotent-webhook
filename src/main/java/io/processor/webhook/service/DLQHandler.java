package io.processor.webhook.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DLQHandler {

  private final JdbcClient jdbcClient;

  /**
   * REQUIRES_NEW forces Spring to open a completely new database transaction. Even if the calling
   * method throws an exception and rolls back its own transaction, this DLQ insert will be safely
   * committed.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void saveToDLQ(
      String idempotencyKey,
      String eventType,
      String payload,
      String reason,
      int attemptCount
  ) {
    log.info("Writing event {} to Dead Letter Queue...", idempotencyKey);
    String sql = """
        INSERT INTO dead_letter_events
        (idempotency_key, event_type, payload, failure_reason, attempt_count, dlq_status)
        VALUES (:key, :type, :payload::jsonb, :reason, :attempts, 'UNRESOLVED')
        """;
    jdbcClient.sql(sql)
        .param("key", idempotencyKey)
        .param("type", eventType)
        .param("payload", payload)
        .param("reason", reason)
        .param("attempts", attemptCount)
        .update();
    log.info("Successfully safely stored event {} in DLQ.", idempotencyKey);
  }
}
