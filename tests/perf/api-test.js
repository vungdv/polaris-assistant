import http from 'k6/http';
import { check, sleep } from 'k6';
import { getAuthToken, getAuthHeaders } from './common/auth.js';

export const options = {
  insecureSkipTLSVerify: true,
  vus: Number(__ENV.VUS || 5),
  duration: __ENV.DURATION || '10s',
  thresholds: {
    http_req_duration: ['p(95)<200'],
    checks: ['rate>0.95'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'https://polaris.local';

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

export function setup() {
  return {
    token: getAuthToken(),
  };
}

export default function (data) {
  const trace = generateTraceparent();
  const headers = {
    'Content-Type': 'application/json',
    'traceparent': trace.traceparent,
    ...getAuthHeaders(data && data.token),
  };

  // 1. Catalog Endpoints
  // 1.1 List / search products
  {
    const res = http.get(`${BASE_URL}/api/v1/products`, { headers });
    check(res, {
      'GET /api/v1/products status is 200': (r) => r.status === 200,
      'GET /api/v1/products has content array': (r) => {
        try {
          const body = JSON.parse(r.body);
          return Array.isArray(body.content) && body.content.length > 0;
        } catch (_) {
          return false;
        }
      },
      'GET /api/v1/products returns X-Trace-Id matching traceparent': (r) => {
        const headerTraceId = r.headers['X-Trace-Id'] || r.headers['x-trace-id'];
        return headerTraceId === trace.traceId;
      },
    });
  }

  // 1.2 Get product by SKU (Existing)
  {
    const res = http.get(`${BASE_URL}/api/v1/products/sku/NG-EARBUD-01`, { headers });
    check(res, {
      'GET /api/v1/products/sku/NG-EARBUD-01 status is 200': (r) => r.status === 200,
      'GET /api/v1/products/sku/NG-EARBUD-01 returns SKU NG-EARBUD-01': (r) => {
        try {
          const body = JSON.parse(r.body);
          return body.sku === 'NG-EARBUD-01';
        } catch (_) {
          return false;
        }
      },
      'GET /api/v1/products/sku/NG-EARBUD-01 returns X-Trace-Id matching traceparent': (r) => {
        const headerTraceId = r.headers['X-Trace-Id'] || r.headers['x-trace-id'];
        return headerTraceId === trace.traceId;
      },
    });
  }

  // 1.3 Get product by SKU (Non-existent -> 404 ProblemDetail)
  {
    const res = http.get(`${BASE_URL}/api/v1/products/sku/NON-EXISTENT`, { headers });
    check(res, {
      'GET /api/v1/products/sku/NON-EXISTENT status is 404': (r) => r.status === 404,
      'GET /api/v1/products/sku/NON-EXISTENT returns RFC 7807 ProblemDetail': (r) => {
        try {
          const body = JSON.parse(r.body);
          return body.status === 404 && body.title === 'Resource Not Found' && !!body.type;
        } catch (_) {
          return false;
        }
      },
      'GET /api/v1/products/sku/NON-EXISTENT returns X-Trace-Id matching traceparent': (r) => {
        const headerTraceId = r.headers['X-Trace-Id'] || r.headers['x-trace-id'];
        return headerTraceId === trace.traceId;
      },
    });
  }

  // 2. Order Endpoints
  // 2.1 Get order status (Existing order ORD-1002)
  {
    const res = http.get(`${BASE_URL}/api/v1/orders/ORD-1002/status`, { headers });
    check(res, {
      'GET /api/v1/orders/ORD-1002/status status is 200': (r) => r.status === 200,
      'GET /api/v1/orders/ORD-1002/status returns ORD-1002 with status CONFIRMED': (r) => {
        try {
          const body = JSON.parse(r.body);
          return body.orderNumber === 'ORD-1002' && body.status === 'CONFIRMED';
        } catch (_) {
          return false;
        }
      },
      'GET /api/v1/orders/ORD-1002/status returns X-Trace-Id matching traceparent': (r) => {
        const headerTraceId = r.headers['X-Trace-Id'] || r.headers['x-trace-id'];
        return headerTraceId === trace.traceId;
      },
    });
  }

  // 2.2 Get order status (Non-existent order ORD-9999 -> 404 ProblemDetail)
  {
    const res = http.get(`${BASE_URL}/api/v1/orders/ORD-9999/status`, { headers });
    check(res, {
      'GET /api/v1/orders/ORD-9999/status status is 404': (r) => r.status === 404,
      'GET /api/v1/orders/ORD-9999/status returns RFC 7807 ProblemDetail': (r) => {
        try {
          const body = JSON.parse(r.body);
          return body.status === 404 && body.title === 'Resource Not Found' && !!body.type;
        } catch (_) {
          return false;
        }
      },
      'GET /api/v1/orders/ORD-9999/status returns X-Trace-Id matching traceparent': (r) => {
        const headerTraceId = r.headers['X-Trace-Id'] || r.headers['x-trace-id'];
        return headerTraceId === trace.traceId;
      },
    });
  }

  // 2.3 Cancel delivered order (ORD-1005 -> 409 Conflict ProblemDetail)
  {
    const res = http.post(`${BASE_URL}/api/v1/orders/ORD-1005/cancel`, null, { headers });
    check(res, {
      'POST /api/v1/orders/ORD-1005/cancel status is 409': (r) => r.status === 409,
      'POST /api/v1/orders/ORD-1005/cancel returns RFC 7807 ProblemDetail': (r) => {
        try {
          const body = JSON.parse(r.body);
          return body.status === 409 && body.title === 'Order State Conflict' && body.type === 'https://polaris.local/errors/conflict';
        } catch (_) {
          return false;
        }
      },
      'POST /api/v1/orders/ORD-1005/cancel returns X-Trace-Id matching traceparent': (r) => {
        const headerTraceId = r.headers['X-Trace-Id'] || r.headers['x-trace-id'];
        return headerTraceId === trace.traceId;
      },
    });
  }

  sleep(0.1);
}
