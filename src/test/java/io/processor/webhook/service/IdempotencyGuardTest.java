package io.processor.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.processor.webhook.domain.model.EventStatus;
import io.processor.webhook.domain.model.ProcessedEvent;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class IdempotencyGuardRaceConditionTest {

  @Autowired
  private IdempotencyGuard idempotencyGuard;

  @Test
  void shouldProcessExactlyOnce_FailsWithoutLocks() throws InterruptedException {
    int concurrentRequests = 50;
    ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);

    // This latch acts as a starting gun. All threads will wait for it to reach 0.
    CountDownLatch startGun = new CountDownLatch(1);
    CountDownLatch finishLine = new CountDownLatch(concurrentRequests);

    // We use an AtomicInteger to safely count how many times the business logic actually runs
    AtomicInteger timesProcessed = new AtomicInteger(0);

    String sharedIdempotencyKey = "race-condition-key-123";

    for (int i = 0; i < concurrentRequests; i++) {
      executor.submit(() -> {
        try {
          startGun.await(); // Threads block here, waiting for the gun

          idempotencyGuard.protect(
              sharedIdempotencyKey,
              "hash123",
              () -> {
                // THIS IS THE BUSINESS LOGIC
                timesProcessed.incrementAndGet();
                return ProcessedEvent.builder()
                    .idempotencyKey(sharedIdempotencyKey)
                    .payloadHash("hash123")
                    .status(EventStatus.COMPLETED)
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
              }
          );
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          finishLine.countDown();
        }
      });
    }

    // FIRE! All 50 threads hit the IdempotencyGuard at the exact same moment
    startGun.countDown();

    // Wait for all threads to finish
    finishLine.await();

    // THE MOMENT OF TRUTH
    System.out.println("Expected executions: 1");
    System.out.println("Actual executions: " + timesProcessed.get());

    // This assertion WILL FAIL. You will see timesProcessed is likely 5, 10, or even 50.
    assertThat(timesProcessed.get()).isEqualTo(1);
  }
}
