// k6 load test for Ledgerline.
// The app must run with the "local" profile (it serves POST /dev/token for test tokens).
// Run (app on your Mac):
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6:1.0.0 run - < loadtest/transfers.js
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const FUNDING_ACCOUNT = '00000000-0000-0000-0000-000000000001';
const ACCOUNT_COUNT = 20;
const JSON_HEADERS = { 'Content-Type': 'application/json' };

// 200/201 (success) and 422 (insufficient funds) are correct answers, not failures.
http.setResponseCallback(http.expectedStatuses(200, 201, 422));

export const options = {
    scenarios: {
        transfers: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 20 },  // ramp up
                { duration: '1m', target: 50 },   // sustained load
                { duration: '30s', target: 0 },   // ramp down
            ],
        },
    },
    // Starting gates. Adjust them to what you actually measure, and record the machine you ran on.
    thresholds: {
        http_req_failed: ['rate<0.01'],
        'http_req_duration{name:transfer}': ['p(95)<500'],
        checks: ['rate>0.99'],
    },
};

function token(subject, scopes) {
    const res = http.post(`${BASE_URL}/dev/token`, JSON.stringify({ subject, scopes }), { headers: JSON_HEADERS });
    check(res, { 'token issued': (r) => r.status === 200 });
    return res.json('accessToken');
}

function auth(tokenValue, extra = {}) {
    return { ...JSON_HEADERS, Authorization: `Bearer ${tokenValue}`, ...extra };
}

// Runs once: get tokens, create accounts owned by the load-test user, and fund them.
export function setup() {
    const userToken = token('load-test-user', ['accounts:read', 'accounts:write', 'transfers:write']);
    const adminToken = token('load-test-admin', ['accounts:read', 'transfers:write', 'ledger:admin']);

    const ids = [];
    for (let i = 0; i < ACCOUNT_COUNT; i++) {
        const created = http.post(`${BASE_URL}/accounts`,
            JSON.stringify({ name: `Load test ${i}`, currency: 'USD' }), { headers: auth(userToken) });
        check(created, { 'account created': (r) => r.status === 201 });
        const id = created.json('id');

        const funded = http.post(`${BASE_URL}/transfers`,
            JSON.stringify({ fromAccountId: FUNDING_ACCOUNT, toAccountId: id, amount: 1000000, description: 'Load test funding' }),
            { headers: auth(adminToken, { 'Idempotency-Key': uuidv4() }) });
        check(funded, { 'account funded': (r) => r.status === 201 });
        ids.push(id);
    }
    return { ids, userToken, adminToken };
}

// Runs repeatedly on every virtual user: one random transfer.
export default function (data) {
    const ids = data.ids;
    const from = ids[Math.floor(Math.random() * ids.length)];
    let to = from;
    while (to === from) {
        to = ids[Math.floor(Math.random() * ids.length)];
    }
    const body = JSON.stringify({ fromAccountId: from, toAccountId: to, amount: 1 + Math.floor(Math.random() * 5000) });
    const params = { headers: auth(data.userToken, { 'Idempotency-Key': uuidv4() }), tags: { name: 'transfer' } };

    const res = http.post(`${BASE_URL}/transfers`, body, params);
    check(res, { 'transfer is 201 or 422': (r) => r.status === 201 || r.status === 422 });

    // 10% of successful transfers: simulate a client retry with the same key and body.
    if (res.status === 201 && Math.random() < 0.1) {
        const retry = http.post(`${BASE_URL}/transfers`, body, { ...params, tags: { name: 'retry' } });
        check(retry, { 'retry is replayed, not charged again': (r) => r.headers['Idempotent-Replayed'] === 'true' });
    }
}

// Runs once at the end: the books must still balance.
export function teardown(data) {
    const res = http.get(`${BASE_URL}/reconciliation`, { headers: auth(data.adminToken) });
    const balanced = res.json('balanced');
    check(res, { 'ledger still balanced after load': () => balanced === true });
    console.log(`Reconciliation after load: balanced=${balanced}, violations=${res.json('violations')}`);
}