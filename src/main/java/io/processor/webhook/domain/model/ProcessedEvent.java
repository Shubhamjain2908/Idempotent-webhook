package io.processor.webhook.domain.model;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ProcessedEvent {
  String idempotencyKey;
  String payloadHash;
  String resultJson;
  EventStatus status;
  Instant createdAt;
  Instant updatedAt;
}
