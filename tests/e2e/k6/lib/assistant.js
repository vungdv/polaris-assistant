import http from 'k6/http';
import { ASSISTANT_BASES, CHAT_TIMEOUT } from './config.js';
import { json } from './http.js';

// Each request goes to the next of ASSISTANT_BASES (round robin per VU; a single base unless ASSISTANT_BASES is set).
let calls = 0;
function api() {
  return `${ASSISTANT_BASES[calls++ % ASSISTANT_BASES.length]}/api/v1/assistant`;
}

/** One chat turn; the sessionId keeps the conversation (and its order draft) together. */
export function chat(token, sessionId, message, turn) {
  const params = json(token);
  params.timeout = CHAT_TIMEOUT;
  params.tags = { turn };
  return http.post(`${api()}/chat`, JSON.stringify({ sessionId, message }), params);
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
  return http.post(`${api()}/sessions/${sessionId}/drafts/${draftId}/confirm`, null, params);
}
