import { form } from './http.js';
import { json } from './http.js';
import http from 'k6/http';
import { TOKEN_URL, CLIENT_ID, ADMIN_TOKEN_URL, ADMIN_API, KC_ADMIN } from './config.js';

/** Password-grant token for a user imported from docker/keycloak/polaris-realm.json. */
export function userToken(username, password) {
  const res = form(TOKEN_URL, { grant_type: 'password', client_id: CLIENT_ID, username, password });
  return res.status === 200 ? res.json('access_token') : null;
}

/** Admin-API token from the master realm (admin-cli password grant). */
export function adminToken() {
  const res = form(ADMIN_TOKEN_URL, { grant_type: 'password', client_id: 'admin-cli', username: KC_ADMIN.username, password: KC_ADMIN.password });
  return res.status === 200 ? res.json('access_token') : null;
}

export function realmRole(token, name) {
  const res = http.get(`${ADMIN_API}/roles/${name}`, json(token));
  return res.status === 200 ? res.json() : null;
}

/** Creates a user with a permanent password and one realm role. Returns 'created' | 'exists' | 'failed:<detail>'. */
export function createUser(token, user, password, role) {
  const created = http.post(`${ADMIN_API}/users`, JSON.stringify({
    username: user.username,
    email: user.email,
    firstName: user.firstName,
    lastName: user.lastName,
    enabled: true,
    emailVerified: true,
    credentials: [{ type: 'password', value: password, temporary: false }],
  }), json(token));
  if (created.status === 409) return 'exists';
  if (created.status !== 201) return `failed:create ${created.status} ${created.body}`;

  const id = created.headers.Location.split('/').pop();
  const mapped = http.post(`${ADMIN_API}/users/${id}/role-mappings/realm`, JSON.stringify([role]), json(token));
  return mapped.status === 204 ? 'created' : `failed:role ${mapped.status} ${mapped.body}`;
}
