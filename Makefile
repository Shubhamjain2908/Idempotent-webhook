.PHONY: chaos-test

chaos-test:
	@echo "==> 1. Starting Database and Redis..."
	docker-compose up -d postgres redis flyway
	@echo "Waiting for DB to be healthy..."
	@sleep 5

	@echo "==> 2. Starting Spring Boot Application in the background..."
	./gradlew bootRun > /dev/null 2>&1 & echo $$! > app.pid
	@echo "Waiting for application to boot (15s)..."
	@sleep 15

	@echo "==> 3. Sending Webhook and INSTANTLY killing the app (Simulating Pod Crash)..."
	# We send the request in the background, wait a fraction of a second, and kill the app
	curl -s -X POST http://localhost:8080/webhooks/payment.succeeded \
		-H "Idempotency-Key: chaos-key-999" \
		-H "Content-Type: application/json" \
		-d '{"userId": "chaos_user", "amount": 100}' &
	@sleep 0.1
	@kill -9 `cat app.pid`
	@rm app.pid
	@echo "Application violently killed mid-flight."

	@echo "==> 4. Restarting Application..."
	./gradlew bootRun > /dev/null 2>&1 & echo $$! > app.pid
	@sleep 15

	@echo "==> 5. Sending Provider Retry (The exact same webhook)..."
	curl -s -X POST http://localhost:8080/webhooks/payment.succeeded \
		-H "Idempotency-Key: chaos-key-999" \
		-H "Content-Type: application/json" \
		-d '{"userId": "chaos_user", "amount": 100}'
	@echo "\n"

	@echo "==> 6. Verifying Database Data Integrity..."
	@echo "Expected: Exactly 1 record. Actual row count:"
	@docker exec webhook-postgres psql -U webhook_user -d webhook_db -t -c \
		"SELECT COUNT(*) FROM processed_events WHERE idempotency_key = 'chaos-key-999';"
	@echo "Matching record details:"
	@docker exec webhook-postgres psql -U webhook_user -d webhook_db -c \
		"SELECT idempotency_key, status, created_at, expires_at FROM processed_events WHERE idempotency_key = 'chaos-key-999';"

	@echo "==> 7. Cleaning up..."
	@kill -9 `cat app.pid`
	@rm app.pid
	@echo "Chaos test complete. If the count is 1, the architecture held."
