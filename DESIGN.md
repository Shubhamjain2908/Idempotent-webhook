# Architectural Design Decisions

This document outlines the trade-offs and rationale behind the idempotency and dual-write mechanisms in this webhook ingestion engine.

## Why Redis + PostgreSQL over PostgreSQL alone?
PostgreSQL with a unique constraint is sufficient for ensuring data integrity, but it fails under high-concurrency "thundering herd" scenarios. If 50 duplicate webhooks arrive simultaneously, Postgres must open 50 connections and evaluate 50 transaction locks. This exhausts the connection pool and degrades overall database performance.
By putting a Redis `SETNX` lock in front of Postgres, we introduce a **fail-open fast path**. Redis handles the sub-millisecond rejection of 99% of in-flight duplicates, while Postgres acts purely as the durable source of truth. If Redis crashes, the system gracefully degrades to rely solely on Postgres.

## Advisory Locks vs. Application-Level Locks (e.g., ShedLock)
Standard pessimistic locking (`SELECT ... FOR UPDATE`) locks actual database rows, which can lead to deadlocks and blocks other queries. Application-level locks (like ShedLock) rely on inserting records into a separate lock table, which creates heavy I/O overhead for high-throughput HTTP requests.
PostgreSQL **Advisory Locks** are lightweight, in-memory locks managed directly by the Postgres engine. Because they are tied to the transaction lifecycle (`pg_try_advisory_xact_lock`), they are automatically released if the application crashes or the transaction rolls back, entirely eliminating the need for manual lock cleanup or TTL management in the database.

## Path to 10x Scale
If traffic scales from 10k to 100k webhooks per minute, this architecture would hit two bottlenecks:
1. **Advisory Lock Namespace:** Postgres advisory locks operate on a single shared memory space. At 10x scale, we would partition the idempotency keys and shard the database, or move to a distributed lock manager (like Redis Redlock), accepting the slight network latency penalty.
2. **Outbox Polling:** The Spring `@Scheduled` relay querying `WHERE published = false` would become CPU-intensive. At scale, this polling mechanism would be replaced with **Change Data Capture (CDC)** using Debezium to stream Write-Ahead Logs (WAL) directly into Kafka with zero query overhead.
