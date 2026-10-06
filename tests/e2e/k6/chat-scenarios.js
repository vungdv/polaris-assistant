// polaris-assistant chat journey per shopper (users from seed-shoppers.js: shopper.0 .. shopper.N-1), one session per iteration:
//   1. search products  2. place order (draft card -> "Submit Order" button)  3. check order status  4. cancel order
// Every turn calls the LLM, so the stack needs GEMINI_API_KEY. Run with: make chat-scenarios
import { check, fail, sleep } from 'k6';
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';
import {
  API_BASE, SKU, SEARCH_QUERY, STAFF, SHOPPER_PREFIX, SHOPPER_COUNT, SHOPPER_PASSWORD, CHAT_VUS, CHAT_ITERATIONS, THINK_TIME_S,
} from './lib/config.js';
import { userToken } from './lib/keycloak.js';
import { json } from './lib/http.js';
import { ensureUnlimitedProduct } from './lib/catalog.js';
import { chat, widget, confirmDraft } from './lib/assistant.js';

const turnDuration = new Trend('chat_turn_duration', true);
const cancelled = new Counter('orders_cancelled');
const notCancellable = new Counter('orders_not_cancellable'); // fulfilment may already have moved the order past PLACED/CONFIRMED

export const options = {
  insecureSkipTLSVerify: true, // local self-signed nginx certificate
  scenarios: {
    chat: { executor: 'per-vu-iterations', vus: Math.min(CHAT_VUS, SHOPPER_COUNT), iterations: CHAT_ITERATIONS, maxDuration: '30m' },
  },
  thresholds: {
    checks: ['rate==1'],
    'http_req_failed{turn:search}': ['rate==0'],
    'http_req_failed{turn:order}': ['rate==0'],
    'http_req_failed{turn:confirm}': ['rate==0'],
    'http_req_failed{turn:status}': ['rate==0'],
    'http_req_failed{turn:cancel}': ['rate==0'],
    chat_turn_duration: ['p(95)<60000'],
  },
};

export function setup() {
  const staff = userToken(STAFF.username, STAFF.password);
  if (!staff) fail(`no token for ${STAFF.username}`);
  ensureUnlimitedProduct(staff, SKU); // known, in-stock product so search and order have something to hit
}

function turn(token, sessionId, message, name) {
  const res = chat(token, sessionId, message, name);
  turnDuration.add(res.timings.duration, { turn: name });
  if (res.status !== 200) console.error(`${name}: ${res.status} ${res.body}`);
  return res;
}

export default function () {
  const username = `${SHOPPER_PREFIX}.${(__VU - 1) % SHOPPER_COUNT}`;
  const token = userToken(username, SHOPPER_PASSWORD);
  if (!token) fail(`no OIDC token for ${username} (run make seed-shoppers)`);
  const sessionId = `k6-${username}-${__VU}-${__ITER}-${Date.now()}`;

  // 1. search products
  const search = turn(token, sessionId, `Find products matching ${SEARCH_QUERY}`, 'search');
  check(search, {
    'search: 200 with a reply': (r) => r.status === 200 && !!r.json('reply'),
    'search: PRODUCT_LIST card': (r) => !!widget(r, 'PRODUCT_LIST'),
  });
  sleep(THINK_TIME_S);

  // 2. place order: the assistant stages a draft, the shopper's button confirms it
  const order = turn(token, sessionId, `I want to order 1 unit of ${SKU} for myself`, 'order');
  const draft = widget(order, 'ORDER_DRAFT');
  check(order, { 'order: ORDER_DRAFT card': () => !!draft });
  if (!draft) return;
  const confirm = confirmDraft(token, sessionId, draft.payload.draftId, `k6-${sessionId}`);
  const confirmed = widget(confirm, 'ORDER_CONFIRMED');
  check(confirm, { 'confirm: 201 with ORDER_CONFIRMED card': (r) => r.status === 201 && !!confirmed });
  if (!confirmed) { console.error(`confirm: ${confirm.status} ${confirm.body}`); return; }
  const orderNumber = confirmed.payload.orderNumber;
  sleep(THINK_TIME_S);

  // 3. check order status
  const status = turn(token, sessionId, `What is the status of order ${orderNumber}?`, 'status');
  check(status, {
    'status: 200 mentioning the order number': (r) => r.status === 200 && String(r.json('reply')).includes(orderNumber),
  });
  sleep(THINK_TIME_S);

  // 4. cancel order (two-step: request, then explicit confirmation)
  const cancel = turn(token, sessionId, `Please cancel order ${orderNumber}`, 'cancel');
  check(cancel, { 'cancel: 200 with a reply': (r) => r.status === 200 && !!r.json('reply') });
  const cancelConfirm = turn(token, sessionId, 'Yes, confirm the cancellation', 'cancel');
  check(cancelConfirm, { 'cancel confirm: 200 with a reply': (r) => r.status === 200 && !!r.json('reply') });

  const final = http.get(`${API_BASE}/api/v1/orders/${orderNumber}`, json(token));
  if (final.status === 200 && final.json('status') === 'CANCELLED') cancelled.add(1);
  else { notCancellable.add(1); console.warn(`${orderNumber} is ${final.status === 200 ? final.json('status') : final.status} after cancel`); }
}

export function handleSummary(data) {
  return { stdout: textSummary(data, { indent: ' ', enableColors: false }) };
}
