# Polaris External API Performance & Contract Test Suite (k6)

This directory contains automated k6 performance and contract validation tests for the Polaris service.

## Test Scope

The k6 test suite (`api-test.js`) verifies:
1. **Catalog Domain Endpoints**:
   - `GET /api/v1/products`: Product listing & search verification (200 OK).
   - `GET /api/v1/products/sku/NG-EARBUD-01`: Exact SKU lookup (200 OK).
   - `GET /api/v1/products/sku/NON-EXISTENT`: Non-existent SKU lookup returning RFC 7807 ProblemDetail (404 Not Found).
2. **Order Domain Endpoints**:
   - `GET /api/v1/orders/ORD-1002/status`: Order status retrieval (200 OK, `CONFIRMED`).
   - `GET /api/v1/orders/ORD-9999/status`: Missing order lookup returning RFC 7807 ProblemDetail (404 Not Found).
   - `POST /api/v1/orders/ORD-1005/cancel`: Attempting to cancel a delivered order returning RFC 7807 ProblemDetail (409 Conflict, "Order State Conflict").
3. **High-Concurrency Order Placement & Dirty Data Validation (`order-concurrency-test.js`)**:
   - Concurrently submits order placement requests (`POST /api/v1/orders`) from multiple VUs competing for limited product inventory.
   - Verifies pessimistic lock serialization and stock deductions.
   - Audits that successful requests return `201 Created` and exhausted stock returns `400 Bad Request` RFC 7807 ProblemDetail (`out-of-stock`).
   - Invariant verification in `teardown()`: validates that `final_stock >= 0`, `total_sold <= initial_stock`, and no overselling or dirty data occurs.
4. **Observability & Distributed Tracing**:
   - Injects W3C standard `traceparent` headers (`00-${traceId}-${spanId}-01`).
   - Validates that the application propagates and echoes the matching `X-Trace-Id` response header.
5. **SLO / SLA Thresholds**:
   - `http_req_duration: ['p(95)<200']` (or `p(95)<500` under write lock contention).
   - `checks: ['rate>0.95']` (check success rate must be greater than 95%).

---

## Running the Tests

### Option 1: Using Local k6

If you have `k6` installed locally:

```bash
# Basic run against default https://polaris.local
k6 run tests/perf/api-test.js

# Target local direct HTTP port (if running without TLS proxy)
k6 run -e BASE_URL=http://localhost:8080 tests/perf/api-test.js

# Provide an explicit JWT bearer token
k6 run -e AUTH_TOKEN="<your_jwt_token>" tests/perf/api-test.js

# Custom VUs and duration
k6 run -e VUS=10 -e DURATION=30s tests/perf/api-test.js
```

### Option 2: Using Docker

Run k6 via Docker container without installing local tooling:

```bash
# Run general API performance & contract suite
docker run --network host --rm -i \
  -v $(pwd)/tests/perf:/scripts -w /scripts \
  -e BASE_URL=https://polaris.local \
  -e VUS=10 \
  -e DURATION=15s \
  grafana/k6 run api-test.js

# Run Order Concurrency & Dirty Data Validation suite (Buyer Saturation)
docker run --network host --rm -i \
  -v $(pwd)/tests/perf:/scripts -w /scripts \
  -e BASE_URL=https://polaris.local \
  -e VUS=20 \
  -e ITERATIONS=50 \
  -e SKU=NG-CHARGER-02 \
  grafana/k6 run order-concurrency-test.js

# Run Mixed Concurrency Suite (Concurrent Buyers + Warehouse Restockers)
docker run --network host --rm -i \
  -v $(pwd)/tests/perf:/scripts -w /scripts \
  -e BASE_URL=https://polaris.local \
  -e VUS=20 \
  -e ITERATIONS=50 \
  -e SKU=NG-HEADPHONE-01 \
  -e MIXED_MODE=true \
  -e RESTOCK_DELTA=5 \
  grafana/k6 run order-concurrency-test.js
```

---

## Configuration Options (Environment Variables)

| Variable | Default | Description |
|---|---|---|
| `BASE_URL` | `https://polaris.local` | Base URL of the Polaris API gateway |
| `KEYCLOAK_URL` | `https://id.polaris.local/realms/polaris/protocol/openid-connect/token` | Keycloak OpenID Connect token URL |
| `CLIENT_ID` | `polaris-local` | Keycloak OAuth2 client ID |
| `USERNAME` | `testuser` | Keycloak test username |
| `PASSWORD` | `testpass` | Keycloak test user password |
| `AUTH_TOKEN` | `""` | Optional direct Bearer JWT (bypasses Keycloak login call) |
| `VUS` | `5` | Number of concurrent virtual users |
| `DURATION` | `10s` | Test run duration (for `api-test.js`) |
| `ITERATIONS` | `50` | Total shared iterations (for `order-concurrency-test.js`) |
| `SKU` | `NG-CHARGER-02` | Target SKU for concurrency test |
| `MIXED_MODE` | `false` | Enable mixed concurrent restocking (`true` / `false`) |
| `RESTOCK_DELTA`| `5` | Stock added per restock operation in mixed mode |
