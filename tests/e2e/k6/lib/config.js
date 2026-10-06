// Environment-driven configuration for the fulfilment e2e (12-factor: nothing hardcoded but local-dev defaults).
export const API_BASE = __ENV.API_BASE || 'http://host.docker.internal:8080';
export const KC_BASE = __ENV.KC_BASE || 'https://id.polaris.local';
export const REALM = __ENV.REALM || 'polaris';
export const TOKEN_URL = `${KC_BASE}/realms/${REALM}/protocol/openid-connect/token`;

// Both users are imported from docker/keycloak/polaris-realm.json; a stale realm is fixed with `make clean && make up`.
// Shopper: linked to a seeded customer (V12 migration / GET /customers/me).
export const SHOPPER = {
  username: __ENV.E2E_USER || 'alice.tran',
  password: __ENV.E2E_PASSWORD || 'testpass',
};
// Staff account used only to prepare the catalog (catalog.write + inventory.write via the admin realm role).
export const STAFF = {
  username: __ENV.E2E_STAFF_USER || 'testuser',
  password: __ENV.E2E_STAFF_PASSWORD || 'testpass',
};

export const CLIENT_ID = __ENV.CLIENT_ID || 'polaris-app';

export const SKU = __ENV.SKU || 'E2E-UNLIMITED-01';
export const MIN_STOCK = Number(__ENV.MIN_STOCK || 1000000000); // "unlimited": top up below this, never depletes in practice
export const BATCH = Number(__ENV.BATCH || 10);
export const TIMEOUT_S = Number(__ENV.TIMEOUT || 120);          // whole fulfilment of all orders
export const POLL_INTERVAL_S = Number(__ENV.POLL_INTERVAL || 2);

// Keycloak admin (master realm, admin-cli) used only to seed users; defaults match docker-compose.yml.
export const KC_ADMIN = {
  username: __ENV.KC_ADMIN_USER || 'admin',
  password: __ENV.KC_ADMIN_PASSWORD || 'admin',
};
export const ADMIN_TOKEN_URL = `${KC_BASE}/realms/master/protocol/openid-connect/token`;
export const ADMIN_API = `${KC_BASE}/admin/realms/${REALM}`;

// Seeded load-test shoppers: shopper.0 .. shopper.{SHOPPER_COUNT-1}, all sharing SHOPPER_PASSWORD.
export const SHOPPER_PREFIX = __ENV.SHOPPER_PREFIX || 'shopper';
export const SHOPPER_COUNT = Number(__ENV.SHOPPER_COUNT || 10);
export const SHOPPER_PASSWORD = __ENV.SHOPPER_PASSWORD || 'testpass';

// polaris-assistant (published on 8081); each chat turn calls the LLM, hence the long request timeout.
export const ASSISTANT_BASE = __ENV.ASSISTANT_BASE || 'http://host.docker.internal:8081';
export const CHAT_TIMEOUT = __ENV.CHAT_TIMEOUT || '120s';
export const CHAT_VUS = Number(__ENV.CHAT_VUS || 1);              // one shopper.N per VU, capped by SHOPPER_COUNT
export const CHAT_ITERATIONS = Number(__ENV.CHAT_ITERATIONS || 1); // full journeys per VU
export const THINK_TIME_S = Number(__ENV.THINK_TIME || 1);
export const SEARCH_QUERY = __ENV.SEARCH_QUERY || 'E2E';     // matches the fixture product name
