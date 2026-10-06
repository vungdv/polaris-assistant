// F4 fulfilment end-to-end (PRD-007 Scenarios 2, 4-7), API first: everything goes through public REST/OIDC.
//   setup (idempotent, repeatable): realm users' tokens -> unlimited-stock product via catalog REST
//   scenario: place 1 order + a batch, poll GET /orders/{n} until DELIVERED with an assignedPartner,
//             assert the batch was won by more than one partner.
// The order numbers and their assignedPartner are logged as an `E2E_ORDERS ORD-1=partner ...` line; tests/e2e/run-fulfilment.sh feeds them to
// verify-kafka-events.sh (the 5 order.*.v1 events on Kafka). Run with: make e2e-fulfilment
import { check, fail } from 'k6';
import { Counter, Gauge } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';
import {
  SKU, BATCH, TIMEOUT_S, SHOPPER, STAFF,
} from './lib/config.js';
import { userToken } from './lib/keycloak.js';
import { ensureUnlimitedProduct } from './lib/catalog.js';
import { placeOrders, awaitDelivered } from './lib/orders.js';

const notDelivered = new Counter('orders_not_delivered');
const placeFailures = new Counter('orders_place_failures');
const winners = new Gauge('batch_distinct_partners');

export const options = {
  insecureSkipTLSVerify: true, // local self-signed nginx certificate
  scenarios: {
    fulfilment: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: `${TIMEOUT_S + 120}s` },
  },
  thresholds: {
    checks: ['rate==1'],
    orders_place_failures: ['count==0'],
    orders_not_delivered: ['count==0'],
    batch_distinct_partners: ['value>1'],
  },
};

export function setup() {
  const staff = userToken(STAFF.username, STAFF.password);
  if (!staff) fail(`no token for ${STAFF.username}`);
  ensureUnlimitedProduct(staff, SKU);
  return { runId: `${Date.now()}-${Math.floor(Math.random() * 1e6)}` };
}

export default function (data) {
  const shopper = () => userToken(SHOPPER.username, SHOPPER.password);
  const token = shopper();
  if (!token) fail(`no OIDC token for ${SHOPPER.username}`);

  // 1 single order + a batch; a fresh Idempotency-Key per run so repeated runs create new orders.
  const keys = ['single'].concat(Array.from({ length: BATCH }, (_, i) => `b${i + 1}`)).map((k) => `e2e-${data.runId}-${k}`);
  const placed = placeOrders(token, SKU, keys);
  const numbers = [];
  placed.forEach((res, i) => {
    const ok = check(res, { [`order ${keys[i]} placed (201)`]: (r) => r.status === 201 });
    if (ok) numbers.push(res.json('orderNumber')); else { placeFailures.add(1); console.error(`${keys[i]}: ${res.status} ${res.body}`); }
  });

  const states = awaitDelivered(numbers, shopper, TIMEOUT_S);
  numbers.forEach((n) => {
    const s = states[n];
    const ok = check(s, {
      [`${n} DELIVERED with assignedPartner`]: (x) => x.status === 'DELIVERED' && !!x.assignedPartner,
    });
    if (!ok) { notDelivered.add(1); console.error(`${n} is ${s.status} (partner ${s.assignedPartner}) after ${TIMEOUT_S}s`); }
  });

  const batchNumbers = numbers.slice(1); // numbers[0] is the single order when it was placed
  const distinct = new Set(batchNumbers.map((n) => states[n].assignedPartner).filter(Boolean));
  winners.add(distinct.size);
  console.log(`batch winners: ${[...distinct].join(', ')} (${distinct.size} distinct)`);
  check(distinct, { 'batch has more than one winning partner': (d) => d.size > 1 });

  // Machine-readable hand-off for verify-kafka-events.sh (k6 has no Kafka client).
  console.log(`E2E_ORDERS ${numbers.map((n) => `${n}=${states[n].assignedPartner}`).join(' ')}`);
}

export function handleSummary(data) {
  return { stdout: textSummary(data, { indent: ' ', enableColors: false }) };
}
