"""Stand-in for the two model APIs the assistant calls (Gemini generateContent, TypeSafe System One).

CI points the assistant at this instead of the live APIs, so the smoke test doesn't depend on an API key's quota. It
answers the turns of tests/e2e/k6/chat-scenarios.js deterministically: it picks the intent from keywords, asks the
assistant to call the tool the turn needs, then replies in text once the tool result is back. Standard library only.
"""
import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SEARCH = "catalog.product.search"
STATUS = "information.lookup.order.status"
PLACE = "commerce.order.place"
CANCEL = "commerce.order.cancel"
GENERAL = "general.conversation"


def classify(message):
    text = message.lower()
    if "cancel" in text:
        return CANCEL
    if "status of order" in text:
        return STATUS
    if re.search(r"\border\b.*\bunit", text):
        return PLACE
    if text.startswith("find products") or "search" in text:
        return SEARCH
    return GENERAL


def gemini_reply(contents):
    """The model's next turn: a function call, or text once the tool results are in."""
    last = contents[-1] if contents else {}
    parts = last.get("parts", [])
    if any("functionResponse" in p for p in parts):
        return [{"text": "Done. The details are in the card above."}]
    message = " ".join(p.get("text", "") for p in parts)
    intent = classify(message)
    if intent == SEARCH:
        match = re.search(r"matching\s+(.+)$", message)
        query = match.group(1).strip() if match else message
        return [{"functionCall": {"name": "search_available_products", "args": {"query": query}}}]
    if intent == PLACE:
        match = re.search(r"unit(?:s)? of\s+(\S+)", message)
        sku = match.group(1) if match else ""
        return [{"functionCall": {"name": "stage_order_draft", "args": {"items": [{"sku": sku, "quantity": 1}]}}}]
    if intent == STATUS:
        match = re.search(r"order\s+(\S+?)\??$", message)
        number = match.group(1) if match else "your order"
        return [{"text": f"Order {number} is being processed."}]
    if intent == CANCEL:
        return [{"text": "Please confirm that you want to cancel the order, or tell me if you changed your mind."}]
    return [{"text": "Hello! I can search products, place an order or check an order for you."}]


def systemone_reply(body):
    message = body.get("state", {}).get("message", "")
    return {"answers": {"intent": {"choice": classify(message), "confidence": 0.99}}}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, status, payload=None):
        data = json.dumps(payload).encode() if payload is not None else b""
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def do_HEAD(self):
        self._send(200)

    def do_GET(self):  # Gemini health probe: GET /v1beta/models/<model>
        self._send(200, {"name": self.path}) if self.path.startswith("/v1beta/models/") else self._send(404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        if self.path.startswith("/v1beta/models/") and self.path.endswith(":generateContent"):
            parts = gemini_reply(body.get("contents", []))
            self._send(200, {
                "candidates": [{"content": {"role": "model", "parts": parts}, "finishReason": "STOP"}],
                "usageMetadata": {"promptTokenCount": 1, "candidatesTokenCount": 1, "totalTokenCount": 2},
                "modelVersion": "model-stub",
            })
        elif self.path == "/v1/systemone":
            self._send(200, systemone_reply(body))
        else:
            self._send(404)

    def log_message(self, fmt, *args):
        print(self.command, self.path, fmt % args, flush=True)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
