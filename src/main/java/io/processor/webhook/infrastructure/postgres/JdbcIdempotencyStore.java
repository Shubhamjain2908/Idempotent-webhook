package io.processor.webhook.infrastructure.postgres;

import io.processor.webhook.domain.model.EventStatus;
import io.processor.webhook.domain.model.ProcessedEvent;
import io.processor.webhook.domain.ports.IdempotencyStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcIdempotencyStore implements IdempotencyStore {
  private final JdbcClient jdbcClient;

  @Override
  public Optional<ProcessedEvent> findByKey(String idempotencyKey) {
    String sql = "SELECT * FROM processed_events WHERE idempotency_key = :key";
    return jdbcClient.sql(sql).param("key", idempotencyKey).query(this::mapRow).optional();
  }

  @Override
  public void save(ProcessedEvent event) {
    String sql = """
        INSERT INTO processed_events
        (idempotency_key, payload_hash, status, result_json, expires_at)
        VALUES (:key, :hash, :status, :json::jsonb, :expires)
        ON CONFLICT (idempotency_key) DO NOTHING
        """;
    jdbcClient.sql(sql)
        .param("key", event.getIdempotencyKey())
        .param("hash", event.getPayloadHash())
        .param("status", event.getStatus().name())
        .param("json", event.getResultJson())
        .param("expires", event.getExpiresAt())
        .update();
  }

  private ProcessedEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
    return ProcessedEvent.builder()
        .idempotencyKey(rs.getString("idempotency_key"))
        .payloadHash(rs.getString("payload_hash"))
        .status(EventStatus.valueOf(rs.getString("status")))
        .resultJson(rs.getString("result_json"))
        .createdAt(rs.getTimestamp("created_at").toInstant())
        .expiresAt(rs.getTimestamp("expires_at").toInstant())
        .build();
  }
}
