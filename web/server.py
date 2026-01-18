#!/usr/bin/env python3
import json
import os
import ssl
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent

HOST = os.environ.get("WEB_SERVER_HOST", "0.0.0.0")
PORT = int(os.environ.get("WEB_SERVER_PORT", "5173"))

GUMLOOP_WEBHOOK_URL = os.environ.get("GUMLOOP_WEBHOOK_URL")
PLAN_SERVER_URL = os.environ.get("PLAN_SERVER_URL", "http://localhost:4890/plans")
SKIP_SSL_VERIFY = os.environ.get("GUMLOOP_SKIP_SSL_VERIFY") == "1"

DEFAULT_SIZE = {"w": 16, "h": 12, "l": 16}
DEFAULT_MAX_BLOCKS = 600
DEFAULT_PALETTE = [
    "minecraft:oak_log",
    "minecraft:oak_planks",
    "minecraft:cobblestone",
    "minecraft:stone_bricks",
    "minecraft:oak_stairs",
    "minecraft:cobblestone_stairs",
    "minecraft:stone_brick_stairs",
    "minecraft:oak_slab",
    "minecraft:cobblestone_slab",
    "minecraft:stone_brick_slab",
    "minecraft:oak_fence",
    "minecraft:cobblestone_wall",
    "minecraft:oak_door",
    "minecraft:oak_trapdoor",
    "minecraft:torch",
    "minecraft:lantern",
    "minecraft:glass",
]


def parse_query_params(url):
    parsed = urllib.parse.urlparse(url)
    params = urllib.parse.parse_qs(parsed.query or "")
    return {k: v[0] for k, v in params.items()}


def find_plan(node, depth=0):
    if depth > 6 or node is None:
        return None
    if isinstance(node, dict):
        if "meta" in node or "ops" in node:
            return node
        if "response" in node:
            found = find_plan(node["response"], depth + 1)
            if found:
                return found
        for value in node.values():
            found = find_plan(value, depth + 1)
            if found:
                return found
        return None
    if isinstance(node, list):
        for item in node:
            found = find_plan(item, depth + 1)
            if found:
                return found
        return None
    if isinstance(node, str):
        try:
            return find_plan(json.loads(node), depth + 1)
        except json.JSONDecodeError:
            return None
    return None


def http_json(url, payload=None, method="GET", headers=None, timeout=20):
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    context = None
    if SKIP_SSL_VERIFY:
        context = ssl._create_unverified_context()
    with urllib.request.urlopen(req, timeout=timeout, context=context) as response:
        body = response.read().decode("utf-8")
        return response.status, body


def preview(text, limit=220):
    if text is None:
        return ""
    if len(text) <= limit:
        return text
    return text[:limit] + "..."


def poll_gumloop_for_plan(run_id, webhook_url, max_attempts=120, interval=1.0):
    params = parse_query_params(webhook_url)
    query = {"run_id": run_id}
    if params.get("user_id"):
        query["user_id"] = params["user_id"]
    if params.get("api_key"):
        query["api_key"] = params["api_key"]
    poll_url = "https://api.gumloop.com/api/v1/get_pl_run?" + urllib.parse.urlencode(query)
    for _ in range(max_attempts):
        status, body = http_json(poll_url, timeout=15)
        if status < 200 or status >= 300:
            time.sleep(interval)
            continue
        try:
            data = json.loads(body)
        except json.JSONDecodeError:
            time.sleep(interval)
            continue
        if data.get("state") == "FAILED":
            raise RuntimeError("Gumloop run failed.")
        outputs = data.get("outputs")
        plan = find_plan(outputs)
        if plan:
            return plan
        time.sleep(interval)
    raise RuntimeError("Timed out waiting for Gumloop output.")


class WebHandler(BaseHTTPRequestHandler):
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

    def _send_file(self, path):
        if not path.exists() or not path.is_file():
            self.send_response(404)
            self.end_headers()
            return
        content = path.read_bytes()
        if path.suffix == ".html":
            mime = "text/html"
        elif path.suffix == ".css":
            mime = "text/css"
        elif path.suffix == ".js":
            mime = "application/javascript"
        elif path.suffix == ".png":
            mime = "image/png"
        elif path.suffix == ".jpg" or path.suffix == ".jpeg":
            mime = "image/jpeg"
        else:
            mime = "application/octet-stream"
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        if self.path == "/" or self.path == "/index.html":
            self._send_file(ROOT_DIR / "index.html")
            return
        file_path = ROOT_DIR / self.path.lstrip("/")
        self._send_file(file_path)

    def do_POST(self):
        if self.path != "/generate":
            self.send_response(404)
            self.end_headers()
            return
        if not GUMLOOP_WEBHOOK_URL:
            self._send_json(500, {"error": "GUMLOOP_WEBHOOK_URL is not set."})
            return
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            self._send_json(400, {"error": "Missing request body."})
            return
        raw = self.rfile.read(length)
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            self._send_json(400, {"error": "Invalid JSON."})
            return
        prompt = payload.get("prompt", "").strip()
        if not prompt:
            self._send_json(400, {"error": "Prompt is required."})
            return
        images = payload.get("images", [])
        gumloop_payload = {
            "prompt": prompt,
            "size": DEFAULT_SIZE,
            "palette": DEFAULT_PALETTE,
            "maxBlocks": DEFAULT_MAX_BLOCKS,
            "images": images,
        }
        try:
            status, body = http_json(GUMLOOP_WEBHOOK_URL, gumloop_payload, method="POST", timeout=25)
        except Exception as exc:
            self._send_json(502, {"error": f"Gumloop request failed: {exc}"})
            return
        if status < 200 or status >= 300:
            self._send_json(502, {"error": f"Gumloop returned status {status}: {preview(body)}"})
            return
        try:
            gumloop_response = json.loads(body)
        except json.JSONDecodeError:
            self._send_json(502, {"error": f"Gumloop returned invalid JSON: {preview(body)}"})
            return
        plan = find_plan(gumloop_response)
        if not plan and gumloop_response.get("run_id"):
            try:
                plan = poll_gumloop_for_plan(gumloop_response["run_id"], GUMLOOP_WEBHOOK_URL)
            except Exception as exc:
                self._send_json(502, {"error": str(exc)})
                return
        if not plan:
            self._send_json(502, {"error": f"Could not find a build plan in Gumloop output: {preview(body)}"})
            return
        if "meta" not in plan or "ops" not in plan:
            self._send_json(502, {"error": "Plan missing meta or ops."})
            return
        try:
            plan_status, plan_body = http_json(PLAN_SERVER_URL, plan, method="POST", timeout=10)
        except Exception as exc:
            self._send_json(502, {"error": f"Plan server error: {exc}"})
            return
        if plan_status < 200 or plan_status >= 300:
            self._send_json(502, {"error": f"Plan server rejected the plan: {preview(plan_body)}"})
            return
        try:
            plan_response = json.loads(plan_body)
        except json.JSONDecodeError:
            self._send_json(502, {"error": "Plan server returned invalid JSON."})
            return
        url = plan_response.get("url")
        if not url:
            self._send_json(502, {"error": "Plan server response missing url."})
            return
        self._send_json(200, {"url": url, "size": DEFAULT_SIZE})


def main():
    server = HTTPServer((HOST, PORT), WebHandler)
    print(f"Web UI listening on http://localhost:{PORT}")
    server.serve_forever()


if __name__ == "__main__":
    main()
