package io.processor.webhook.exception;

public class ConcurrentWebhookException extends RuntimeException {
  public ConcurrentWebhookException(String message) {
    super(message);
  }
}
