import http from 'k6/http';
import { sleep } from 'k6';
import { API_BASE, POLL_INTERVAL_S } from './config.js';
import { json } from './http.js';

/** Places one order for the caller's own customer; the Idempotency-Key makes the request replay-safe. */
export function orderRequest(token, sku, idempotencyKey) {
  const opts = json(token);
  opts.headers['Idempotency-Key'] = idempotencyKey;
  return { method: 'POST', url: `${API_BASE}/api/v1/orders`, body: JSON.stringify({ items: [{ sku, quantity: 1 }] }), params: opts };
}

export function placeOrders(token, sku, keys) {
  return http.batch(keys.map((k) => orderRequest(token, sku, k)));
}

/**
 * Polls GET /orders/{n} until every order is DELIVERED or the deadline passes.
 * `tokenFn` is called per round so a long wait never outlives the access token.
 * Returns { orderNumber: {status, assignedPartner} }.
 */
export function awaitDelivered(numbers, tokenFn, timeoutS) {
  const state = {};
  const deadline = Date.now() + timeoutS * 1000;
  for (;;) {
    const opts = json(tokenFn());
    const pending = numbers.filter((n) => !state[n] || state[n].status !== 'DELIVERED');
    if (pending.length === 0) break;
    http.batch(pending.map((n) => ({ method: 'GET', url: `${API_BASE}/api/v1/orders/${n}`, params: opts })))
      .forEach((res, i) => {
        state[pending[i]] = res.status === 200
          ? { status: res.json('status'), assignedPartner: res.json('assignedPartner') || null }
          : { status: `HTTP_${res.status}`, assignedPartner: null };
      });
    if (Date.now() >= deadline) break;
    sleep(POLL_INTERVAL_S);
  }
  return state;
}
