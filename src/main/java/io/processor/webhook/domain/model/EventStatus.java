package io.processor.webhook.domain.model;

public enum EventStatus {
  COMPLETED, FAILED      // Used for terminal validation errors, not transient DB drops
}
