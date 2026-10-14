#!/usr/bin/env python3
"""Real host loopback listener with synthetic code-server-shaped HTTP responses."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import sys
import urllib.parse

root = Path(sys.argv[1])
fixture = json.loads((root / "fixture.json").read_text())
port = fixture["http_port"]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def do_GET(self):
        current = json.loads((root / "fixture.json").read_text())
        if self.path == "/healthz":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"status":"broken"}' if current.get("bad_health") else b'{"status":"alive"}')
        elif self.path == "/" and self.headers.get("Cookie"):
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"<html>synthetic vscode workbench</html>")
        else:
            self.send_response(302)
            self.send_header("Location", "/login")
            self.end_headers()

    def do_POST(self):
        current = json.loads((root / "fixture.json").read_text())
        data = self.rfile.read(min(int(self.headers.get("Content-Length", "0")), 4096))
        yaml = (root / "home/orchestella/.config/code-server/config.yaml").read_text()
        password = yaml.split("password: ", 1)[1].splitlines()[0]
        supplied = urllib.parse.parse_qs(data.decode()).get("password", [""])[0]
        if self.path != "/login" or supplied != password or current.get("reject_auth"):
            self.send_response(401)
            self.end_headers()
            return
        suffix = json.loads((root / "var/lib/orchestella/features/code-server-cookie.json").read_text())["suffix"]
        self.send_response(302)
        self.send_header("Set-Cookie", "code-server-session-" + suffix + "=synthetic-private-cookie; HttpOnly; Path=/")
        self.send_header("Location", "/")
        self.end_headers()


ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
