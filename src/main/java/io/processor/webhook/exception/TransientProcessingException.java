package io.processor.webhook.exception;

public class TransientProcessingException extends RuntimeException {
  public TransientProcessingException(String message) {
    super(message);
  }
}
