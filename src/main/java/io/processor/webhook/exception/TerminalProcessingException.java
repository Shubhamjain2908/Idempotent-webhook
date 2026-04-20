package io.processor.webhook.exception;

public class TerminalProcessingException extends RuntimeException {
  public TerminalProcessingException(String message, Throwable cause) {
    super(message, cause);
  }
}
