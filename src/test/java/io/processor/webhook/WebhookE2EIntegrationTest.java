package io.processor.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookE2EIntegrationTest {

  // 1. Spin up a real Postgres DB
  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  // 2. Spin up a real Redis instance
  @Container
  @ServiceConnection
  static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

//  @Autowired
//  private TestRestTemplate restTemplate;

  @Test
  void shouldHandleConcurrentIdenticalWebhooksExactlyOnce() throws InterruptedException {
    String url = "/webhooks/payment.succeeded";
    String idempotencyKey = "e2e-test-key-999";
    String payload = "{\"userId\": \"user_123\", \"amount\": 5000}";

    HttpHeaders headers = new HttpHeaders();
    headers.set("Idempotency-Key", idempotencyKey);
    headers.set("Content-Type", "application/json");
    HttpEntity<String> request = new HttpEntity<>(payload, headers);

    int threadCount = 20;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(1);
    CountDownLatch finishLine = new CountDownLatch(threadCount);

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger conflictCount = new AtomicInteger(0);

    // 3. Fire 20 HTTP requests at the exact same time
    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          latch.await();
          ResponseEntity<String> response = null; // restTemplate.postForEntity(url, request, String.class);

          if (response.getStatusCode() == HttpStatus.ACCEPTED) {
            successCount.incrementAndGet();
          } else if (response.getStatusCode() == HttpStatus.CONFLICT || response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
            conflictCount.incrementAndGet();
          }
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          finishLine.countDown();
        }
      });
    }

    latch.countDown(); // Start!
    finishLine.await();

    // 4. Assert the distributed system held its ground
    // ONE request should succeed. The other 19 should be rejected (likely by Redis, maybe by Postgres).
    System.out.println("Successful HTTP 202s: " + successCount.get());
    System.out.println("Rejected HTTP 409/429s: " + conflictCount.get());

    assertThat(successCount.get()).isEqualTo(1);
    assertThat(conflictCount.get()).isEqualTo(threadCount - 1);
  }
}
