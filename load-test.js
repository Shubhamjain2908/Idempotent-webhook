import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
    scenarios: {
        // Scenario 1: Spam the same key to test duplicate rejection
        duplicate_spam: {
            executor: 'constant-vus',
            vus: 50,
            duration: '10s',
            exec: 'testDuplicates',
        },
        // Scenario 2: High throughput of unique keys to test actual processing speed
        unique_traffic: {
            executor: 'constant-vus',
            vus: 50,
            duration: '10s',
            exec: 'testThroughput',
        },
    },
};

const BASE_URL = 'http://localhost:8080/webhooks/payment.succeeded';
const payload = JSON.stringify({ userId: 'user_123', amount: 100 });

// Shared key for the duplicate test
const SHARED_KEY = 'k6-shared-key-12345';

export function testDuplicates() {
    const params = { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': SHARED_KEY } };
    const res = http.post(BASE_URL, payload, params);

    // We expect most of these to be 202 (cached response) or 409 (locked)
    check(res, {
        'is status 202 or 409': (r) => r.status === 202 || r.status === 409,
    });
}

export function testThroughput() {
    const params = { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': uuidv4() } };
    const res = http.post(BASE_URL, payload, params);

    // We expect ALL of these to be 202 Accepted
    check(res, {
        'is status 202': (r) => r.status === 202,
    });
}
