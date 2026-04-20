package io.processor.webhook.service;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelay {

  private final JdbcClient jdbcClient;

  // Runs every 2 seconds
  @Scheduled(fixedDelay = 2000)
  public void publishPendingEvents() {
    // 1. Fetch unpublished events (ordered by oldest first)
    String fetchSql = """
        SELECT id, aggregate_type, payload
        FROM outbox_events 
        WHERE published = false 
        ORDER BY created_at ASC 
        LIMIT 100
        """;
    List<Map<String, Object>> pendingEvents = jdbcClient.sql(fetchSql).query().listOfRows();
    if (pendingEvents.isEmpty()) {
      return;
    }
    for (Map<String, Object> event : pendingEvents) {
      try {
        // 2. SEND TO KAFKA / EXTERNAL SYSTEM
        // kafkaTemplate.send(event.get("aggregate_type").toString(), event.get("payload").toString());
        log.info(
            "Published outbox event {} to topic {}",
            event.get("id"),
            event.get("aggregate_type")
        );
        // 3. MARK AS PUBLISHED
        jdbcClient.sql("UPDATE outbox_events SET published = true WHERE id = :id")
            .param("id", event.get("id")).update();

      } catch (Exception e) {
        // If Kafka is down, we catch the error and move on.
        // We DO NOT update published=true. It will simply be picked up again in 2 seconds!
        log.error("Failed to publish event {}. Will retry.", event.get("id"), e);
      }
    }
  }
}
