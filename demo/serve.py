#!/usr/bin/env python3
"""Serve the built gwt-webworker demo over http and open it in a browser.

A Web Worker needs a real origin: ``new Worker("file:///...")`` is rejected with
"cannot be accessed from origin 'null'". So the demo cannot be opened by double-clicking
``index.html`` - it has to come from an http server. That is all this script is.

    python demo/serve.py                 # serve target/gwt/demo on 127.0.0.1:8080
    python demo/serve.py --build         # run the Maven build first, then serve
    python demo/serve.py --port 8081 --no-open
"""

import argparse
import functools
import http.server
import os
import shutil
import socketserver
import subprocess
import sys
import threading
import webbrowser

DEFAULT_ROOT = os.path.join("target", "gwt", "demo")
DEFAULT_PORT = 8080

BUILD_HINT = "mvn -o -DskipTests -Pgwt-demo package"

# Python's mimetypes module reads the Windows registry, and a misconfigured one maps .js to
# text/plain - which makes the browser refuse the GWT bootstrap. Pin the types this demo needs
# instead of trusting the host's registry.
EXTRA_TYPES = {
    ".js": "text/javascript",
    ".mjs": "text/javascript",
    ".css": "text/css",
    ".html": "text/html; charset=utf-8",
    ".json": "application/json",
    ".map": "application/json",
    ".wasm": "application/wasm",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".gif": "image/gif",
    ".txt": "text/plain; charset=utf-8",
}


class DemoHandler(http.server.SimpleHTTPRequestHandler):
    def guess_type(self, path):
        extension = os.path.splitext(path)[1].lower()
        if extension in EXTRA_TYPES:
            return EXTRA_TYPES[extension]
        return super().guess_type(path)

    def end_headers(self):
        # GWT's .nocache.js is requested by a stable name, so a cached copy survives a rebuild and
        # silently keeps booting the previous .cache.js. A dev server must never cache.
        self.send_header("Cache-Control", "no-store, must-revalidate")
        super().end_headers()

    def log_request(self, code="-", size="-"):
        # Silence the per-request noise but keep failures visible.
        if isinstance(code, int) and code >= 400:
            sys.stderr.write("  %s -> %s\n" % (self.requestline, code))


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def find_maven():
    for name in ("mvn", "mvn.cmd", "mvn.bat"):
        found = shutil.which(name)
        if found:
            return found
    return None


def build(root):
    """Run the Maven profile that compiles both demo modules into `root`."""
    maven = find_maven()
    if maven is None:
        print("找不到 mvn，无法自动构建。请手动执行：%s" % BUILD_HINT, file=sys.stderr)
        return 1

    print("$ %s" % BUILD_HINT)
    # -o: the local repository is the only source; nothing here should need the network.
    return subprocess.call([maven, "-o", "-DskipTests", "-Pgwt-demo", "package"])


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("root", nargs="?", default=DEFAULT_ROOT,
                        help="directory to serve (default: %s)" % DEFAULT_ROOT)
    parser.add_argument("-p", "--port", type=int, default=DEFAULT_PORT,
                        help="port on 127.0.0.1 (default: %d)" % DEFAULT_PORT)
    parser.add_argument("--no-open", action="store_true",
                        help="do not launch a browser")
    parser.add_argument("--build", action="store_true",
                        help="run the Maven demo build before serving")
    args = parser.parse_args()

    root = os.path.abspath(args.root)

    if args.build:
        code = build(root)
        if code != 0:
            print("构建失败（退出码 %s），未启动服务。" % code, file=sys.stderr)
            return code

    if not os.path.isfile(os.path.join(root, "index.html")):
        print("找不到 %s" % os.path.join(root, "index.html"), file=sys.stderr)
        print("先构建 demo：\n    %s" % BUILD_HINT, file=sys.stderr)
        print("或让本脚本代劳：\n    python %s --build" % os.path.relpath(__file__),
              file=sys.stderr)
        return 1

    handler = functools.partial(DemoHandler, directory=root)
    # Bound to loopback on purpose - this is a local test server, never expose it.
    try:
        httpd = Server(("127.0.0.1", args.port), handler)
    except OSError as error:
        print("端口 %d 不可用：%s" % (args.port, error), file=sys.stderr)
        print("换一个端口，例如：python %s --port %d" % (os.path.relpath(__file__), args.port + 1),
              file=sys.stderr)
        return 1

    url = "http://127.0.0.1:%d/" % args.port
    # flush: with stdout piped (IDE consoles, CI) the buffer would otherwise hold these back and the
    # script would look like it hung without ever printing the URL to open.
    print("serving %s" % root, flush=True)
    print("open    %s" % url, flush=True)
    print("stop    Ctrl+C", flush=True)
    if not args.no_open:
        threading.Timer(0.6, webbrowser.open, args=(url,)).start()

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")
    finally:
        httpd.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
