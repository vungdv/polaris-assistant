// Polaris Products API through the Gravitee gateway (gateway/gravitee/apis/polaris-products.*).
// Usage: make apim-k6   (stack + apim profile up, API deployed). Override the load with VUS / DURATION.
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { SHOPPER } from '../../e2e/k6/lib/config.js';
import { userToken } from '../../e2e/k6/lib/keycloak.js';

const GATEWAY_BASE = __ENV.GATEWAY_BASE || 'http://host.docker.internal:8082';
const SKU = __ENV.SKU || 'NG-EARBUD-01'; // seeded by V2__seed_data.sql

export const options = {
  insecureSkipTLSVerify: true, // local self-signed nginx certificate (Keycloak token endpoint)
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || '1m',
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

export function setup() {
  const token = userToken(SHOPPER.username, SHOPPER.password);
  if (!token) fail(`no token for ${SHOPPER.username}`);
  return { token };
}

export default function ({ token }) {
  const res = http.get(`${GATEWAY_BASE}/api/v1/products?query=${SKU}`, { headers: { Authorization: `Bearer ${token}` } });
  check(res, {
    'search 200': (r) => r.status === 200,
    'served by the gateway': (r) => r.headers['X-Gravitee-Transaction-Id'] !== undefined,
    [`finds ${SKU}`]: (r) => r.status === 200 && r.json('content').some((p) => p.sku === SKU),
  });
  sleep(0.5);
}
