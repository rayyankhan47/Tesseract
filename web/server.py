#!/usr/bin/env python3
import json
import os
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent

HOST = os.environ.get("WEB_SERVER_HOST", "0.0.0.0")
PORT = int(os.environ.get("WEB_SERVER_PORT", "5173"))

# Java mod embedded HTTP server (added in Step 9 of REFACTOR.md).
# POST /build  →  { prompt, imageBase64?, imageMimeType? }  →  { meta, ops }
BUILD_SERVER_URL = os.environ.get("BUILD_SERVER_URL", "http://localhost:4891/build")

# Plan store (tools/plan_server.py).
# POST /plans  →  { meta, ops }  →  { id, url }
PLAN_SERVER_URL = os.environ.get("PLAN_SERVER_URL", "http://localhost:4890/plans")


def http_json(url, payload=None, method="GET", headers=None, timeout=20):
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    with urllib.request.urlopen(req, timeout=timeout) as response:
        body = response.read().decode("utf-8")
        return response.status, body


def preview(text, limit=220):
    if text is None:
        return ""
    if len(text) <= limit:
        return text
    return text[:limit] + "..."


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
        elif path.suffix in (".jpg", ".jpeg"):
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
        if self.path in ("/", "/index.html"):
            self._send_file(ROOT_DIR / "index.html")
            return
        self._send_file(ROOT_DIR / self.path.lstrip("/"))

    def do_POST(self):
        if self.path != "/generate":
            self.send_response(404)
            self.end_headers()
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

        # Extract first attached image (if any) from the data URL the browser sends.
        image_base64 = None
        image_mime_type = None
        images = payload.get("images", [])
        if images:
            data_url = images[0].get("dataUrl", "")
            if ";base64," in data_url:
                header, image_base64 = data_url.split(";base64,", 1)
                image_mime_type = header.replace("data:", "") or "image/png"

        # Send to Java mod agent pipeline (implemented in Step 9 of REFACTOR.md).
        build_payload = {"prompt": prompt}
        if image_base64:
            build_payload["imageBase64"] = image_base64
            build_payload["imageMimeType"] = image_mime_type

        try:
            build_status, build_body = http_json(
                BUILD_SERVER_URL, build_payload, method="POST", timeout=120
            )
        except Exception as exc:
            self._send_json(502, {"error": f"Build server unavailable: {exc}"})
            return

        if build_status < 200 or build_status >= 300:
            self._send_json(502, {"error": f"Build server returned {build_status}: {preview(build_body)}"})
            return

        try:
            plan = json.loads(build_body)
        except json.JSONDecodeError:
            self._send_json(502, {"error": f"Build server returned invalid JSON: {preview(build_body)}"})
            return

        if "meta" not in plan or "ops" not in plan:
            self._send_json(502, {"error": "Build server response missing meta or ops."})
            return

        # Store plan in plan_server and get a shareable URL for /tesseract paste.
        try:
            plan_status, plan_body = http_json(PLAN_SERVER_URL, plan, method="POST", timeout=10)
        except Exception as exc:
            self._send_json(502, {"error": f"Plan server error: {exc}"})
            return

        if plan_status < 200 or plan_status >= 300:
            self._send_json(502, {"error": f"Plan server rejected plan: {preview(plan_body)}"})
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

        self._send_json(200, {"url": url})


def main():
    server = HTTPServer((HOST, PORT), WebHandler)
    print(f"Web UI listening on http://localhost:{PORT}")
    server.serve_forever()


if __name__ == "__main__":
    main()
