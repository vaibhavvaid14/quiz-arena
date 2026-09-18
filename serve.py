"""Zero-dependency dev server for Quiz Arena.

Browsers refuse to load ES modules from file:// URLs, so the app must be served
over HTTP. Python's stock http.server takes MIME types from the Windows
registry, which often maps .js to text/plain and makes module scripts fail;
this server pins the correct types and disables caching.

Usage:  python serve.py [--port 8000] [--open]
"""

import argparse
import functools
import http.server
import pathlib
import socketserver
import webbrowser

ROOT = pathlib.Path(__file__).resolve().parent

MIME_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".mjs": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".ico": "image/x-icon",
    ".md": "text/markdown; charset=utf-8",
}


class QuizRequestHandler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {**http.server.SimpleHTTPRequestHandler.extensions_map, **MIME_TYPES}

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        super().end_headers()

    def log_message(self, format, *args):  # noqa: A002 - signature from the base class
        # Only surface failed requests; log_error() passes a single argument.
        if len(args) < 3 or str(args[1]).startswith(("4", "5")):
            super().log_message(format, *args)


class ThreadingServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    parser = argparse.ArgumentParser(description="Serve Quiz Arena locally.")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--open", action="store_true", help="open the app in the default browser")
    args = parser.parse_args()

    handler = functools.partial(QuizRequestHandler, directory=str(ROOT))
    with ThreadingServer(("127.0.0.1", args.port), handler) as server:
        url = f"http://127.0.0.1:{args.port}/"
        print(f"Quiz Arena running at {url}  (tests: {url}tests/)  — Ctrl+C to stop")
        if args.open:
            webbrowser.open(url)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\nStopped.")


if __name__ == "__main__":
    main()
