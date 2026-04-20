package io.processor.webhook.service;

import io.processor.webhook.domain.model.ProcessedEvent;
import io.processor.webhook.domain.ports.IdempotencyStore;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IdempotencyGuard {

  private final IdempotencyStore idempotencyStore;

  public ProcessedEvent protect(
      String idempotencyKey,
      String payloadHash,
      Supplier<ProcessedEvent> businessLogic
  ) {

    // 1. Check if we've seen this before
    Optional<ProcessedEvent> existing = idempotencyStore.findByKey(idempotencyKey);
    if (existing.isPresent()) {
      return existing.get(); // Return cached response
    }

    // 2. We haven't seen it, so execute the business logic!
    ProcessedEvent newEvent = businessLogic.get();

    // 3. Save the result to the DB (Using our ON CONFLICT DO NOTHING query)
    idempotencyStore.save(newEvent);

    return newEvent;
  }
}
