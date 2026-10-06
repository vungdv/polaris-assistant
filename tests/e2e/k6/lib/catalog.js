import http from 'k6/http';
import { fail } from 'k6';
import { API_BASE, MIN_STOCK } from './config.js';
import { json } from './http.js';

const MAX_INT = 2147483647;

/**
 * Idempotent: creates the product with effectively unlimited stock if absent (409 = already there),
 * then tops the stock up (PUT .../inventory delta) whenever it fell below MIN_STOCK. Public REST only.
 */
export function ensureUnlimitedProduct(staffToken, sku) {
  const opts = json(staffToken);
  const create = http.post(`${API_BASE}/api/v1/products`, JSON.stringify({
    sku, name: 'E2E Unlimited Product', description: 'Fulfilment e2e fixture, stock is topped up on every run',
    price: 1.0, stockQuantity: MAX_INT, active: true,
  }), opts);
  if (create.status !== 201 && create.status !== 409) fail(`create product ${sku}: ${create.status} ${create.body}`);

  const get = http.get(`${API_BASE}/api/v1/products/sku/${sku}`, opts);
  if (get.status !== 200) fail(`read product ${sku}: ${get.status} ${get.body}`);
  const stock = get.json('stockQuantity');
  if (stock < MIN_STOCK) {
    const top = http.put(`${API_BASE}/api/v1/products/sku/${sku}/inventory`, JSON.stringify({ delta: MAX_INT - stock }), opts);
    if (top.status !== 200) fail(`top up ${sku}: ${top.status} ${top.body}`);
  }
}
