package io.processor.webhook.api;

import io.processor.webhook.service.IdempotencyGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
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
            return null; // Replace with actual ProcessedEvent creation
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
}
