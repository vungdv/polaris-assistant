import http from 'k6/http';

export function json(token) {
  return { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } };
}

export function form(url, body) {
  return http.post(url, body, { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
}
