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

    CountDownLatch startGun = new CountDownLatch(1);
    CountDownLatch finishLine = new CountDownLatch(concurrentRequests);
    AtomicInteger timesProcessed = new AtomicInteger(0);

    String sharedIdempotencyKey = "race-condition-key-123";

    for (int i = 0; i < concurrentRequests; i++) {
      executor.submit(() -> {
        try {
          startGun.await();

          idempotencyGuard.protect(
              sharedIdempotencyKey,
              "hash123",
              () -> {
                // 1. ADD A DELAY: Simulate a slow external API call.
                // This ensures Thread 1 holds the lock long enough for
                // Threads 2-50 to crash into the locked door.
                try { Thread.sleep(50); } catch (InterruptedException e) {}

                timesProcessed.incrementAndGet();
                return ProcessedEvent.builder()
                    .idempotencyKey(sharedIdempotencyKey)
                    .payloadHash("hash123")
                    .status(EventStatus.COMPLETED)
                    // 2. FIX THE DB CRASH: Provide an actual JSON string
                    .resultJson("{\"message\": \"success\"}")
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
              }
          );
        } catch (Exception e) {
          // 3. STOP SWALLOWING EXCEPTIONS!
          // Print out what is actually killing the threads.
          // You SHOULD see 49 "ConcurrentWebhookException"s in your console.
          System.out.println("Thread failed: " + e.getMessage());
        } finally {
          finishLine.countDown();
        }
      });
    }

    startGun.countDown();
    finishLine.await();

    System.out.println("Expected executions: 1");
    System.out.println("Actual executions: " + timesProcessed.get());

    assertThat(timesProcessed.get()).isEqualTo(1);
  }
}
