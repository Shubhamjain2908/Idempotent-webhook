package io.processor.webhook.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.processor.webhook.domain.model.ProcessedEvent;
import io.processor.webhook.domain.ports.IdempotencyStore;
import io.processor.webhook.domain.ports.InFlightLock;
import io.processor.webhook.exception.ConcurrentWebhookException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;


@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyGuard {

  private final InFlightLock inFlightLock;
  private final IdempotencyStore idempotencyStore;
  private final TransactionTemplate transactionTemplate;
  private final MeterRegistry meterRegistry;

  public ProcessedEvent protect(
      String idempotencyKey,
      String payloadHash,
      Supplier<ProcessedEvent> businessLogic
  ) {
    AtomicReference<String> outcomeTag = new AtomicReference<>("unknown");
    long startTime = System.nanoTime();

    // 1. FAST PATH: Redis SETNX
    // This stops 99% of concurrent duplicates before they even touch the database pool.
    boolean lockAcquired = inFlightLock.acquire(idempotencyKey, Duration.ofSeconds(30));
    if (!lockAcquired) {
      outcomeTag.set("redis_duplicate_rejected");
      log.warn("Fast-path lock rejected for key: {}", idempotencyKey);
      throw new ConcurrentWebhookException("Webhook is currently being processed (caught by Redis)");
    }
    try {
      // 2. TRANSACTION BOUNDARY BEGINS
      return transactionTemplate.execute(status -> {
        // 3. DURABLE PATH: Postgres Advisory Lock (Our fail-safe if Redis drops the ball)
        boolean locked = idempotencyStore.acquireAdvisoryLock(idempotencyKey);
        if (!locked) {
          outcomeTag.set("db_concurrent_rejected");
          log.warn("Advisory lock rejected for key: {}", idempotencyKey);
          throw new ConcurrentWebhookException(
              "Webhook is currently being processed by another transaction");
        }
        // 4. CHECK DB
        Optional<ProcessedEvent> existing = idempotencyStore.findByKey(idempotencyKey);
        if (existing.isPresent()) {
          // Validation: Did they reuse the key for a different payload?
          if (!existing.get().getPayloadHash().equals(payloadHash)) {
            throw new IllegalArgumentException("Idempotency key reused with different payload");
          }
          log.info("Idempotency hit! Returning cached result for key: {}", idempotencyKey);
          return existing.get();
        }
        // 5. PROCESS
        log.info("Idempotency miss. Processing new event for key: {}", idempotencyKey);
        ProcessedEvent newEvent = businessLogic.get();
        // 6. SAVE RESULT
        idempotencyStore.save(newEvent);
        return newEvent;
      });

    } finally {
      // 7. CLEANUP
      // We must release the Redis lock ONLY AFTER the database transaction fully commits.
      // If we released it earlier, a new request could bypass Redis while the DB was still saving!
      inFlightLock.release(idempotencyKey);

      Timer.builder("webhook.idempotency.check")
          .description("Latency of the idempotency guard")
          .tag("outcome", outcomeTag.get())
          .publishPercentileHistogram()
          .register(meterRegistry)
          .record(Duration.ofNanos(System.nanoTime() - startTime));
    }
  }
}
