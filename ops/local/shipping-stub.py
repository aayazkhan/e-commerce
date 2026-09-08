#!/usr/bin/env python3
"""Local stub for SHIPPING_PROVIDER_BASE_URL: implements the minimal contract
HttpShippingProvider expects (POST /quotes, POST /shipments, GET /shipments/{id},
POST /shipments/{id}/cancel), so checkout-service's saga can create a shipment
end-to-end without a real carrier integration.
"""
import http.server
import json
import re
import time
import uuid


class Handler(http.server.BaseHTTPRequestHandler):
    def _respond(self, payload, status=200):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_body(self):
        length = int(self.headers.get("Content-Length", 0))
        return self.rfile.read(length) if length else b"{}"

    def do_POST(self):
        self._read_body()
        if self.path == "/quotes":
            self._respond({
                "id": f"quote_{uuid.uuid4().hex[:8]}",
                "amountMinor": 4999,
                "expiresAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 3600)),
            })
        elif self.path == "/shipments":
            self._respond({
                "id": f"ship_{uuid.uuid4().hex[:8]}",
                "status": "LABEL_CREATED",
                "trackingNumber": f"TRK{uuid.uuid4().hex[:10].upper()}",
                "carrier": "StubCarrier",
            })
        elif re.match(r"^/shipments/[^/]+/cancel$", self.path):
            self._respond({"id": self.path.split("/")[2], "status": "CANCELLED", "trackingNumber": None, "carrier": "StubCarrier"})
        else:
            self._respond({"message": "not found"}, status=404)

    def do_GET(self):
        match = re.match(r"^/shipments/([^/]+)$", self.path)
        if match:
            self._respond({"id": match.group(1), "status": "IN_TRANSIT", "trackingNumber": f"TRK{match.group(1)}", "carrier": "StubCarrier"})
        else:
            self._respond({"message": "not found"}, status=404)

    def log_message(self, format, *args):
        pass


if __name__ == "__main__":
    http.server.HTTPServer(("localhost", 8096), Handler).serve_forever()
