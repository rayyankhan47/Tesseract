#!/usr/bin/env python3
import json
import os
import secrets
import string
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse

HOST = os.environ.get("PLAN_SERVER_HOST", "0.0.0.0")
PORT = int(os.environ.get("PLAN_SERVER_PORT", "4890"))
PUBLIC_HOST = os.environ.get("PLAN_SERVER_PUBLIC_HOST", "localhost")
STORE_DIR = os.environ.get("PLAN_STORE_DIR", "plan_store")

ALPHABET = string.ascii_letters + string.digits


def generate_id(length=8):
    return "".join(secrets.choice(ALPHABET) for _ in range(length))


def store_plan(plan_id, plan_data):
    os.makedirs(STORE_DIR, exist_ok=True)
    path = os.path.join(STORE_DIR, f"{plan_id}.json")
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(plan_data, handle, separators=(",", ":"))
    return path


def load_plan(plan_id):
    path = os.path.join(STORE_DIR, f"{plan_id}.json")
    if not os.path.exists(path):
        return None
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


class PlanHandler(BaseHTTPRequestHandler):
    def _send_json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_text(self, status, message):
        body = message.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_POST(self):
        if self.path != "/plans":
            self._send_text(404, "Not found")
            return
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            self._send_text(400, "Missing request body")
            return
        raw = self.rfile.read(length)
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            self._send_text(400, "Invalid JSON")
            return
        if not isinstance(data, dict) or "meta" not in data or "ops" not in data:
            self._send_text(400, "Payload must be a plan with meta and ops")
            return
        plan_id = generate_id()
        store_plan(plan_id, data)
        url = f"http://{PUBLIC_HOST}:{PORT}/plans/{plan_id}"
        self._send_json(200, {"id": plan_id, "url": url})

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            self._send_text(200, "ok")
            return
        if not parsed.path.startswith("/plans/"):
            self._send_text(404, "Not found")
            return
        plan_id = parsed.path.split("/")[2]
        if not plan_id:
            self._send_text(400, "Missing plan id")
            return
        plan = load_plan(plan_id)
        if plan is None:
            self._send_text(404, "Plan not found")
            return
        self._send_json(200, plan)


def main():
    server = HTTPServer((HOST, PORT), PlanHandler)
    print(f"Plan server listening on http://{PUBLIC_HOST}:{PORT}")
    server.serve_forever()


if __name__ == "__main__":
    main()
