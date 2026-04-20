package io.processor.webhook.api;

import io.processor.webhook.domain.model.EventStatus;
import io.processor.webhook.domain.model.ProcessedEvent;
import io.processor.webhook.exception.ConcurrentWebhookException;
import io.processor.webhook.service.EventProcessor;
import io.processor.webhook.service.IdempotencyGuard;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/webhooks")
@RequiredArgsConstructor
public class WebhookController {

  private final IdempotencyGuard idempotencyGuard;
  private final EventProcessor eventProcessor;

  @PostMapping("/{eventType}")
  public ResponseEntity<String> receive(
      @PathVariable String eventType,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody String payload
  ) {
    // 1. Inject context into MDC (Mapped Diagnostic Context)
    MDC.put("idempotency_key", idempotencyKey);
    MDC.put("event_type", eventType);

    try {
      log.info("Received incoming webhook payload");
      // In a real app, you'd pass a real business logic supplier here
      var result = idempotencyGuard.protect(
          idempotencyKey, String.valueOf(payload.hashCode()), () -> {
            log.info("Executing core business logic");
            // 1. Call the real processor! This writes to the outbox via Phase 5 logic.
            eventProcessor.processAndEmit(idempotencyKey, eventType, payload);

            // 2. Return the actual object instead of null
            return ProcessedEvent.builder()
                .idempotencyKey(idempotencyKey)
                .payloadHash(String.valueOf(payload.hashCode()))
                .status(EventStatus.COMPLETED)
                .resultJson("{\"message\": \"success\"}")
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();
          }
      );
      log.info("Core business logic executed successfully: {}", result);
      return ResponseEntity.accepted().body("Acknowledged");
    } finally {
      // 2. CRITICAL: Thread pools reuse threads. If you don't clear this,
      // the next request handled by this thread will have the wrong ID!
      MDC.clear();
    }
  }

  // 3. Map the concurrency exception to HTTP 409 Conflict
  @ExceptionHandler(ConcurrentWebhookException.class)
  public ResponseEntity<String> handleConcurrentWebhook(ConcurrentWebhookException e) {
    // We log at DEBUG or INFO level, not ERROR, because this is expected behavior under load!
    log.info("Rejected duplicate request: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT).body("Webhook is currently being processed.");
  }
}
