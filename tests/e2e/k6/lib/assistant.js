import http from 'k6/http';
import { ASSISTANT_BASE, CHAT_TIMEOUT } from './config.js';
import { json } from './http.js';

const API = `${ASSISTANT_BASE}/api/v1/assistant`;

/** One chat turn; the sessionId keeps the conversation (and its order draft) together. */
export function chat(token, sessionId, message, turn) {
  const params = json(token);
  params.timeout = CHAT_TIMEOUT;
  params.tags = { turn };
  return http.post(`${API}/chat`, JSON.stringify({ sessionId, message }), params);
}

export function widget(res, type) {
  const widgets = res.status >= 200 && res.status < 300 ? res.json('widgets') || [] : [];
  return widgets.find((w) => w.type === type);
}

/** The shopper's "Submit Order" button on an ORDER_DRAFT card. */
export function confirmDraft(token, sessionId, draftId, idempotencyKey) {
  const params = json(token);
  params.headers['Idempotency-Key'] = idempotencyKey;
  params.tags = { turn: 'confirm' };
  return http.post(`${API}/sessions/${sessionId}/drafts/${draftId}/confirm`, null, params);
}
