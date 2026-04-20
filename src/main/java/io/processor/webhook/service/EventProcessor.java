package io.processor.webhook.service;

import io.processor.webhook.exception.TerminalProcessingException;
import io.processor.webhook.exception.TransientProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventProcessor {

  private final DLQHandler dlqHandler;

  /**
   * @Retryable automatically intercepts exceptions. maxAttempts = 3: Initial try + 2 retries.
   * backoff: Base 1000ms, multiplier 2 (1s, 2s, 4s). random = true: This is the JITTER! It adds
   * randomness to prevent Thundering Herds.
   */
  @Retryable(
      retryFor = {TransientProcessingException.class},
      maxAttempts = 3,
      backoff = @Backoff(delay = 1000, multiplier = 2.0, random = true))
  public void processPayload(String idempotencyKey, String eventType, String payload) {
    log.info("Attempting to process event: {}", idempotencyKey);
    // Simulating a flaky external API or database deadlock
    if (Math.random() > 0.1) {
      log.error("Transient error occurred while processing!");
      throw new TransientProcessingException("External API timed out");
    }
    log.info("Successfully processed event: {}", idempotencyKey);
  }

  /**
   * The @Recover method is the ultimate safety net. If all 3 attempts fail, Spring Retry
   * automatically routes the execution here.
   */
  @Recover
  public void recover(
      TransientProcessingException e,
      String idempotencyKey,
      String eventType,
      String payload
  ) {
    log.error("All retries exhausted for key: {}. Routing to DLQ.", idempotencyKey);
    // Send it to the Dead Letter Queue
    dlqHandler.saveToDLQ(idempotencyKey, eventType, payload, e.getMessage(), 3);
    // Rethrow or handle so the IdempotencyGuard knows to mark the main event as FAILED
    throw new TerminalProcessingException("Event sent to DLQ", e);
  }
}
