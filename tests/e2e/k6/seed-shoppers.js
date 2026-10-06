// Seeds load-test shoppers in the polaris realm via the Keycloak Admin REST API: shopper.0 .. shopper.{N-1}, realm role `shopper`
// (same as ben.nguyen). shopper.0-9 are normally imported from docker/keycloak/polaris-realm.json with customers from Flyway V17;
// this tops up an already-running realm (Keycloak assigns random IDs here, so customers link by verified email on first use; they need a
// customer row with the same email, which V17 provides for shopper.0-9). Idempotent: an existing username (HTTP 409) is skipped. Run with: make seed-shoppers
import { fail } from 'k6';
import { Counter } from 'k6/metrics';
import { SHOPPER_PREFIX, SHOPPER_COUNT, SHOPPER_PASSWORD } from './lib/config.js';
import { adminToken, realmRole, createUser } from './lib/keycloak.js';

const created = new Counter('shoppers_created');
const existing = new Counter('shoppers_existing');
const failed = new Counter('shoppers_failed');

export const options = {
  insecureSkipTLSVerify: true, // local self-signed nginx certificate
  scenarios: { seed: { executor: 'shared-iterations', vus: 1, iterations: 1 } },
  thresholds: { shoppers_failed: ['count==0'] },
};

export default function () {
  const token = adminToken();
  if (!token) fail('no admin token (check KC_ADMIN_USER / KC_ADMIN_PASSWORD)');
  const role = realmRole(token, 'shopper');
  if (!role) fail('realm role "shopper" not found in realm');

  for (let i = 0; i < SHOPPER_COUNT; i++) {
    const username = `${SHOPPER_PREFIX}.${i}`;
    const result = createUser(token, {
      username, email: `${username}@example.com`, firstName: 'Shopper', lastName: `${i}`,
    }, SHOPPER_PASSWORD, role);
    if (result === 'created') created.add(1);
    else if (result === 'exists') existing.add(1);
    else { failed.add(1); console.error(`${username}: ${result}`); }
  }
}
