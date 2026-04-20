package io.processor.webhook.domain.ports;

import io.processor.webhook.domain.model.ProcessedEvent;
import java.util.Optional;

public interface IdempotencyStore {

  Optional<ProcessedEvent> findByKey(String idempotencyKey);

  void save(ProcessedEvent event);
}
