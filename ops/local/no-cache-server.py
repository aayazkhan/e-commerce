#!/usr/bin/env python3
"""Static file server for the sellerApp wasmJs build that disables all caching.

Plain `python3 -m http.server` sends Last-Modified but no Cache-Control, so browsers
heuristically cache sellerApp.js/*.wasm and keep serving a stale build after a rebuild --
painful during iteration since the page looks unchanged with no error. This sends
Cache-Control: no-store on every response instead.
"""
import http.server
import sys

port = int(sys.argv[1]) if len(sys.argv) > 1 else 3000
directory = sys.argv[2] if len(sys.argv) > 2 else "."


class NoCacheHandler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()


http.server.test(
    HandlerClass=lambda *args, **kwargs: NoCacheHandler(*args, directory=directory, **kwargs),
    port=port,
)
