package io.processor.webhook.service;

import io.processor.webhook.domain.model.ProcessedEvent;
import io.processor.webhook.domain.ports.IdempotencyStore;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyGuard {

  private final IdempotencyStore idempotencyStore;
  private final TransactionTemplate transactionTemplate;

  public ProcessedEvent protect(String idempotencyKey, String payloadHash, Supplier<ProcessedEvent> businessLogic) {

    // Everything inside this block runs in a single Database Transaction
    return transactionTemplate.execute(status -> {

      // 1. ACQUIRE LOCK FIRST
      boolean locked = idempotencyStore.acquireAdvisoryLock(idempotencyKey);
      if (!locked) {
        // Another thread got here at the exact same millisecond and holds the lock.
        // We throw an exception (which rolls back this thread's transaction)
        // In an actual web controller, we'd map this exception to a 409 Conflict.
        log.warn("Concurrent execution detected for key: {}", idempotencyKey);
        throw new RuntimeException("ConcurrentWebhookException: Webhook is currently being processed");
      }

      // 2. CHECK CACHE (We are now the only thread allowed to ask this question for this key)
      Optional<ProcessedEvent> existing = idempotencyStore.findByKey(idempotencyKey);
      if (existing.isPresent()) {
        return existing.get();
      }

      // 3. EXECUTE BUSINESS LOGIC
      ProcessedEvent newEvent = businessLogic.get();

      // 4. SAVE RESULT
      idempotencyStore.save(newEvent);

      return newEvent;
    }); // <-- TRANSACTION COMMITS HERE (Advisory Lock automatically releases!)
  }
}
