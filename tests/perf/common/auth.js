import http from 'k6/http';

export const DEFAULT_KEYCLOAK_URL = 'https://id.polaris.local/realms/polaris/protocol/openid-connect/token';
export const DEFAULT_CLIENT_ID = 'polaris-local';
export const DEFAULT_USERNAME = 'testuser';
export const DEFAULT_PASSWORD = 'testpass';

/**
 * Retrieves a Bearer JWT access token from Keycloak using OAuth2 Resource Owner Password Credentials Grant,
 * or returns the explicit token if provided via AUTH_TOKEN environment variable or options.
 *
 * @param {Object} [options]
 * @param {string} [options.token] - Explicit token override (falls back to __ENV.AUTH_TOKEN)
 * @param {string} [options.keycloakUrl] - Keycloak token endpoint URL (falls back to __ENV.KEYCLOAK_URL)
 * @param {string} [options.clientId] - OAuth2 client ID (falls back to __ENV.CLIENT_ID)
 * @param {string} [options.username] - User username (falls back to __ENV.USERNAME)
 * @param {string} [options.password] - User password (falls back to __ENV.PASSWORD)
 * @returns {string|null} The access token or null if authentication failed
 */
export function getAuthToken(options = {}) {
  const token = options.token || __ENV.AUTH_TOKEN;
  if (token) {
    return token;
  }

  const keycloakUrl = options.keycloakUrl || __ENV.KEYCLOAK_URL || DEFAULT_KEYCLOAK_URL;
  const clientId = options.clientId || __ENV.CLIENT_ID || DEFAULT_CLIENT_ID;
  const username = options.username || __ENV.USERNAME || DEFAULT_USERNAME;
  const password = options.password || __ENV.PASSWORD || DEFAULT_PASSWORD;

  const payload = {
    grant_type: 'password',
    client_id: clientId,
    username: username,
    password: password,
  };

  const params = {
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
  };

  const res = http.post(keycloakUrl, payload, params);
  if (res.status === 200) {
    try {
      const body = JSON.parse(res.body);
      return body.access_token || null;
    } catch (e) {
      console.error('Failed to parse Keycloak token response:', e);
      return null;
    }
  } else {
    console.error(`Keycloak auth returned status ${res.status}: ${res.body}`);
    return null;
  }
}

/**
 * Creates Authorization header object if token is provided.
 *
 * @param {string|null} token
 * @returns {Object} { Authorization: `Bearer ${token}` } or {}
 */
export function getAuthHeaders(token) {
  if (!token) {
    return {};
  }
  return {
    Authorization: `Bearer ${token}`,
  };
}

export const authenticate = getAuthToken;

export default getAuthToken;
