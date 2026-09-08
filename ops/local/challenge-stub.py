#!/usr/bin/env python3
"""Local stub for CHALLENGE_DELIVERY_URL: accepts identity-service's OTP/verification
POSTs and just logs them, so registration/login flows can complete without a real
email/SMS provider configured."""
import http.server
import json


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        try:
            print("challenge delivered:", json.dumps(json.loads(body)))
        except Exception:
            print("challenge delivered (raw):", body)
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b"{}")

    def log_message(self, format, *args):
        pass


if __name__ == "__main__":
    http.server.HTTPServer(("localhost", 8097), Handler).serve_forever()
