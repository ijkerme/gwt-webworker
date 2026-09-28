#!/usr/bin/env python
"""Serve the verification war and drive it in headless Chrome.

The page POSTs its verdict back to /report; reading the DOM with `chrome --dump-dom` is not
reliable here because --virtual-time-budget truncates the dump at a nondeterministic point while
worker messages are still in flight.

Usage: python verify/run-e2e.py <war-dir> <port> [chrome.exe]
"""
import http.server
import os
import socketserver
import subprocess
import sys
import tempfile
import threading
import time

WAR = sys.argv[1]
PORT = int(sys.argv[2])
CHROME = sys.argv[3] if len(sys.argv) > 3 else \
    r"C:\Program Files\Google\Chrome\Application\chrome.exe"

result = {}
done = threading.Event()


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=WAR, **kw)

    def do_GET(self):
        if self.path.startswith('/report.gif'):
            import urllib.parse
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            result['body'] = 'VERDICT ' + q.get('verdict', [''])[0] + '\n' + q.get('log', [''])[0]
            done.set()
        # serve the requested file (or 404) as usual
        return super().do_GET()

    def log_message(self, *a):
        pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True


srv = Server(('127.0.0.1', PORT), Handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()
time.sleep(1)

profile = tempfile.mkdtemp(prefix='gwtverify-')
proc = subprocess.Popen([
    CHROME, '--headless=new', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
    '--disk-cache-size=0', '--user-data-dir=' + profile,
    'http://127.0.0.1:%d/index.html' % PORT,
], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

if not done.wait(timeout=60):
    print('TIMEOUT: the harness never reported back')
    proc.kill()
    srv.shutdown()
    sys.exit(2)

time.sleep(0.5)
proc.kill()
srv.shutdown()
print(result['body'])
sys.exit(0 if result['body'].startswith('VERDICT PASS') else 1)
