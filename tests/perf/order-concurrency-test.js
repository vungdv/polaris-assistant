import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { getAuthToken, getAuthHeaders } from './common/auth.js';

// Custom metrics for order & inventory concurrency validation
const successfulOrders = new Counter('successful_orders');
const outOfStockOrders = new Counter('out_of_stock_orders');
const successfulRestocks = new Counter('successful_restocks');
const unexpectedErrors = new Counter('unexpected_errors');

export const options = {
  insecureSkipTLSVerify: true,
  scenarios: {
    order_concurrency: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 20),
      iterations: Number(__ENV.ITERATIONS || 50),
      maxDuration: __ENV.MAX_DURATION || '30s',
    },
  },
  thresholds: {
    'http_req_duration': ['p(95)<500'],
    'unexpected_errors': ['count==0'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'https://polaris.local';
const TARGET_SKU = __ENV.SKU || 'NG-CHARGER-02';
const MIXED_MODE = __ENV.MIXED_MODE === 'true';
const RESTOCK_DELTA = Number(__ENV.RESTOCK_DELTA || 5);

function generateTraceparent() {
  const hex = '0123456789abcdef';
  let traceId = '';
  for (let i = 0; i < 32; i++) {
    traceId += hex[Math.floor(Math.random() * 16)];
  }
  let spanId = '';
  for (let i = 0; i < 16; i++) {
    spanId += hex[Math.floor(Math.random() * 16)];
  }
  return {
    traceparent: `00-${traceId}-${spanId}-01`,
    traceId: traceId,
  };
}

function generateUUID() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function setup() {
  const token = getAuthToken();

  // Inspect target product inventory before test run
  const headers = {
    'Content-Type': 'application/json',
    ...getAuthHeaders(token),
  };

  const productRes = http.get(`${BASE_URL}/api/v1/products/sku/${TARGET_SKU}`, { headers });
  let initialStock = 0;
  if (productRes.status === 200) {
    try {
      const product = JSON.parse(productRes.body);
      initialStock = (product.stockQuantity !== undefined) ? product.stockQuantity : product.stockQty;
      console.log(`[SETUP] Target product SKU: ${TARGET_SKU}, Initial Stock: ${initialStock}, Mixed Mode: ${MIXED_MODE}`);
    } catch (e) {
      console.error('Failed to parse product stock:', e);
    }
  } else {
    console.error(`Failed to fetch target product ${TARGET_SKU}, status: ${productRes.status}`);
  }

  return {
    token: token,
    sku: TARGET_SKU,
    initialStock: initialStock,
    customerId: 1,
    mixedMode: MIXED_MODE,
  };
}

export default function (data) {
  const trace = generateTraceparent();
  const headers = {
    'Content-Type': 'application/json',
    'traceparent': trace.traceparent,
    ...getAuthHeaders(data && data.token),
  };

  // If MIXED_MODE is enabled, every 4th request simulates an inventory restock/adjustment by warehouse staff
  const isRestockOperation = data.mixedMode && (__ITER % 4 === 0);

  if (isRestockOperation) {
    const restockPayload = JSON.stringify({ delta: RESTOCK_DELTA });
    const res = http.put(`${BASE_URL}/api/v1/products/sku/${data.sku}/inventory`, restockPayload, { headers });

    if (res.status === 200) {
      successfulRestocks.add(1);
      check(res, {
        'Restock: status 200 OK': (r) => r.status === 200,
        'Restock: returns updated stockQuantity': (r) => {
          try {
            return JSON.parse(r.body).stockQuantity >= 0;
          } catch (_) {
            return false;
          }
        },
      });
    } else {
      unexpectedErrors.add(1);
      console.error(`Unexpected restock error: ${res.status} - ${res.body}`);
      check(res, { 'Unexpected restock error encountered': () => false });
    }
  } else {
    // Standard order placement operation
    const idempotencyKey = `concur-${generateUUID()}`;
    const orderHeaders = Object.assign({}, headers, { 'Idempotency-Key': idempotencyKey });

    const payload = JSON.stringify({
      customerId: data.customerId || 1,
      items: [
        {
          sku: data.sku,
          quantity: 1,
        },
      ],
    });

    const res = http.post(`${BASE_URL}/api/v1/orders`, payload, { headers: orderHeaders });

    if (res.status === 201) {
      successfulOrders.add(1);
      check(res, {
        'Order placed: status 201 Created': (r) => r.status === 201,
        'Order placed: has Location header': (r) => !!r.headers['Location'] || !!r.headers['location'],
        'Order placed: status is PLACED': (r) => {
          try {
            return JSON.parse(r.body).status === 'PLACED';
          } catch (_) {
            return false;
          }
        },
        'Order placed: returns matching X-Trace-Id': (r) => {
          const tid = r.headers['X-Trace-Id'] || r.headers['x-trace-id'];
          return tid === trace.traceId;
        },
      });
    } else if (res.status === 400) {
      outOfStockOrders.add(1);
      check(res, {
        'Inventory exhausted: status 400 Bad Request': (r) => r.status === 400,
        'Inventory exhausted: RFC 7807 problem details returned': (r) => {
          try {
            const body = JSON.parse(r.body);
            return body.status === 400 &&
                   body.title === 'Insufficient Stock' &&
                   body.type === 'https://polaris.local/errors/out-of-stock';
          } catch (_) {
            return false;
          }
        },
        'Inventory exhausted: returns matching X-Trace-Id': (r) => {
          const tid = r.headers['X-Trace-Id'] || r.headers['x-trace-id'];
          return tid === trace.traceId;
        },
      });
    } else {
      unexpectedErrors.add(1);
      console.error(`Unexpected HTTP response: ${res.status} - ${res.body}`);
      check(res, {
        'Unexpected error status encountered': () => false,
      });
    }
  }

  sleep(0.05);
}

export function teardown(data) {
  const headers = {
    'Content-Type': 'application/json',
    ...getAuthHeaders(data && data.token),
  };

  const productRes = http.get(`${BASE_URL}/api/v1/products/sku/${data.sku}`, { headers });
  if (productRes.status === 200) {
    try {
      const product = JSON.parse(productRes.body);
      const finalStock = (product.stockQuantity !== undefined) ? product.stockQuantity : product.stockQty;

      console.log('====================================================');
      console.log('       POLARIS CONCURRENCY & DIRTY-DATA AUDIT       ');
      console.log('====================================================');
      console.log(`Product SKU            : ${data.sku}`);
      console.log(`Initial Stock          : ${data.initialStock}`);
      console.log(`Final Stock in DB      : ${finalStock}`);
      console.log(`Mixed Mode (Restocks)? : ${data.mixedMode ? 'YES' : 'NO'}`);
      console.log(`Non-Negative Stock?    : ${finalStock >= 0 ? 'PASS (>= 0)' : 'FAIL (NEGATIVE STOCK!)'}`);
      console.log('====================================================');
    } catch (e) {
      console.error('Failed to parse final product inventory:', e);
    }
  }
}
