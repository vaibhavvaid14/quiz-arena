"""HTTP server: JSON REST API under /api plus the static front end.

Standard library only (http.server + sqlite3), so it runs on any Python 3.10+
with nothing to install.

API (all JSON; player-scoped routes need an `X-Player-Key` header)
  GET    /api/health
  GET    /api/catalog                       topics with question counts + scoring rules
  POST   /api/players          {name}       claim a name -> {id, name, key}
  GET    /api/me                            who does this key belong to
  GET    /api/me/history                    stats, topic mastery, past attempts
  DELETE /api/me/history                    delete finished attempts
  POST   /api/attempts         {config}     start a quiz -> state
  GET    /api/attempts/{id}                 current state (resume)
  DELETE /api/attempts/{id}                 discard an unfinished quiz
  POST   /api/attempts/{id}/answer  {position, optionId|null}
  POST   /api/attempts/{id}/next    {position}
  POST   /api/attempts/{id}/finish
  GET    /api/attempts/{id}/results         summary + full review
  GET    /api/leaderboard?limit=20
"""

import argparse
import http.server
import json
import mimetypes
import os
import pathlib
import random
import re
import sys
import tempfile
import time
import traceback
import urllib.parse
import webbrowser

from . import db, seed
from .errors import ApiError, NotFound, ValidationError
from .service import QuizService, now_ms

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_DB_PATH = ROOT / "data" / "quiz.db"
MAX_BODY_BYTES = 64 * 1024
MAX_DRAIN_BYTES = 1024 * 1024

# Only these paths are ever served as static files (never data/, server/, .git ...).
STATIC_PREFIXES = ("css/", "js/")
STATIC_FILES = ("index.html",)
TEST_PREFIXES = ("tests/",)  # only in --test mode, which uses a throwaway database

MIME_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".ico": "image/x-icon",
}

SECURITY_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Content-Security-Policy": (
        "default-src 'self'; script-src 'self'; "
        # The UI loads its display fonts from Google Fonts: the stylesheet comes from
        # fonts.googleapis.com and the font files it points at from fonts.gstatic.com.
        "style-src 'self' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; "
        "img-src 'self' data:; "
        "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'self'"
    ),
}

ATTEMPT = r"(?P<attempt>[A-Za-z0-9_-]{8,64})"
ROUTES = [
    ("GET", r"/api/health", "health", False),
    ("GET", r"/api/catalog", "catalog", False),
    ("POST", r"/api/players", "create_player", False),
    ("GET", r"/api/me", "me", True),
    ("GET", r"/api/me/history", "history", True),
    ("DELETE", r"/api/me/history", "clear_history", True),
    ("POST", r"/api/attempts", "create_attempt", True),
    ("GET", rf"/api/attempts/{ATTEMPT}", "get_state", True),
    ("DELETE", rf"/api/attempts/{ATTEMPT}", "discard", True),
    ("POST", rf"/api/attempts/{ATTEMPT}/answer", "answer", True),
    ("POST", rf"/api/attempts/{ATTEMPT}/next", "next", True),
    ("POST", rf"/api/attempts/{ATTEMPT}/finish", "finish", True),
    ("GET", rf"/api/attempts/{ATTEMPT}/results", "results", True),
    ("GET", r"/api/leaderboard", "leaderboard", False),
]
COMPILED_ROUTES = [(method, re.compile(f"^{pattern}$"), name, auth) for method, pattern, name, auth in ROUTES]


class QuizServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, *, db_path, clock=now_ms, rng=None, test_mode=False, quiet=False):
        super().__init__(address, RequestHandler)
        self.db_path = db_path
        self.clock = clock
        self.rng = rng or random.Random()
        self.test_mode = test_mode
        self.quiet = quiet

    def service(self, conn):
        return QuizService(conn, clock=self.clock, rng=self.rng)


class RequestHandler(http.server.BaseHTTPRequestHandler):
    server_version = "QuizArena/2.0"
    protocol_version = "HTTP/1.1"

    # ---------------------------------------------------------------- dispatch

    def do_GET(self):
        self._dispatch("GET")

    def do_POST(self):
        self._dispatch("POST")

    def do_DELETE(self):
        self._dispatch("DELETE")

    def do_HEAD(self):
        self._dispatch("HEAD")

    def do_PUT(self):
        self._dispatch("PUT")

    def do_PATCH(self):
        self._dispatch("PATCH")

    def _dispatch(self, method):
        started = time.perf_counter()
        parsed = urllib.parse.urlsplit(self.path)
        path = urllib.parse.unquote(parsed.path)
        try:
            # Consume the body before anything can fail: an unread body left on a
            # keep-alive connection would be parsed as the start of the next request.
            self._body = self._consume_body()
            if path.startswith("/api/") or path == "/api":
                self._handle_api(method, path, urllib.parse.parse_qs(parsed.query))
            elif method in ("GET", "HEAD"):
                self._serve_static(path, head=method == "HEAD")
            else:
                self._send_json(405, {"error": {"code": "method_not_allowed", "message": "Method not allowed."}})
        except ConnectionError:
            self.close_connection = True  # client went away mid-response; nothing to tell it
            return
        except ApiError as error:
            self._send_error_safely(error.status, error.code, error.message)
        except Exception:  # noqa: BLE001 - last-resort handler: log, never leak internals
            traceback.print_exc()
            self._send_error_safely(500, "internal", "Something went wrong on the server.")
        finally:
            self._log_request(method, path, started)

    def _send_error_safely(self, status, code, message):
        try:
            self._send_json(status, {"error": {"code": code, "message": message}})
        except ConnectionError:
            self.close_connection = True

    def _handle_api(self, method, path, query):
        allowed = []
        for route_method, pattern, name, needs_auth in COMPILED_ROUTES:
            match = pattern.match(path)
            if not match:
                continue
            allowed.append(route_method)
            if route_method != method:
                continue
            conn = db.connect(self.server.db_path)
            try:
                service = self.server.service(conn)
                player = service.authenticate(self.headers.get("X-Player-Key", "").strip()) if needs_auth else None
                status, payload = getattr(self, f"api_{name}")(service, player, match.groupdict(), query)
            finally:
                conn.close()
            self._send_json(status, payload)
            return
        if allowed:
            raise ApiError("Method not allowed.", status=405, code="method_not_allowed")
        raise NotFound("No such API endpoint.")

    # ---------------------------------------------------------------- endpoints

    def api_health(self, service, player, params, query):
        return 200, {"ok": True, "time": self.server.clock()}

    def api_catalog(self, service, player, params, query):
        return 200, service.catalog()

    def api_create_player(self, service, player, params, query):
        return 201, service.create_player(self._read_json())

    def api_me(self, service, player, params, query):
        return 200, player

    def api_history(self, service, player, params, query):
        return 200, service.history(player)

    def api_clear_history(self, service, player, params, query):
        return 200, service.clear_history(player)

    def api_create_attempt(self, service, player, params, query):
        return 201, service.create_attempt(player, self._read_json())

    def api_get_state(self, service, player, params, query):
        return 200, service.get_state(player, params["attempt"])

    def api_discard(self, service, player, params, query):
        service.discard(player, params["attempt"])
        return 200, {"discarded": True}

    def api_answer(self, service, player, params, query):
        return 200, service.answer(player, params["attempt"], self._read_json())

    def api_next(self, service, player, params, query):
        return 200, service.next(player, params["attempt"], self._read_json())

    def api_finish(self, service, player, params, query):
        return 200, service.finish(player, params["attempt"])

    def api_results(self, service, player, params, query):
        return 200, service.results(player, params["attempt"])

    def api_leaderboard(self, service, player, params, query):
        raw = query.get("limit", ["20"])[0]
        if not raw.isdigit():
            raise ValidationError("'limit' must be a positive integer.")
        # Public route; a valid player key additionally returns that player's own rank.
        key = self.headers.get("X-Player-Key", "").strip()
        viewer = None
        if key:
            try:
                viewer = service.authenticate(key)
            except ApiError:
                viewer = None
        return 200, service.leaderboard(int(raw), viewer)

    # ---------------------------------------------------------------- io helpers

    def _consume_body(self):
        if self.headers.get("Transfer-Encoding"):
            self.close_connection = True
            raise ApiError("Chunked request bodies are not supported.", status=411, code="length_required")
        length = self.headers.get("Content-Length", "0")
        if not length.isdigit():
            self.close_connection = True
            raise ValidationError("Invalid Content-Length header.")
        size = int(length)
        if size > MAX_BODY_BYTES:
            # Drain moderately oversized bodies so the client reliably receives the 413
            # (closing a socket with unread data makes Windows send a TCP reset instead).
            # Anything bigger is cut off; either way the connection is not reused.
            if size <= MAX_DRAIN_BYTES:
                self.rfile.read(size)
            self.close_connection = True
            raise ApiError("Request body too large.", status=413, code="too_large")
        return self.rfile.read(size) if size else b""

    def _read_json(self):
        if not self._body.strip():
            return {}
        try:
            return json.loads(self._body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise ValidationError("Request body is not valid JSON.") from None

    def _send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for name, value in SECURITY_HEADERS.items():
            self.send_header(name, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _serve_static(self, path, *, head=False):
        requested = path.lstrip("/") or "index.html"
        if requested.endswith("/"):
            requested += "index.html"
        target = (ROOT / requested).resolve()
        # Decide on the RESOLVED path: "js/../server/app.py" must not pass as "js/...".
        if ROOT not in target.parents or not target.is_file():
            raise NotFound("File not found.")
        relative = target.relative_to(ROOT).as_posix()
        prefixes = STATIC_PREFIXES + (TEST_PREFIXES if self.server.test_mode else ())
        if relative not in STATIC_FILES and not relative.startswith(prefixes):
            raise NotFound("File not found.")

        body = target.read_bytes()
        content_type = MIME_TYPES.get(target.suffix.lower()) or mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache")
        for name, value in SECURITY_HEADERS.items():
            self.send_header(name, value)
        self.end_headers()
        if not head:
            self.wfile.write(body)

    def _log_request(self, method, path, started):
        if self.server.quiet:
            return
        status = getattr(self, "_status", 0)
        if path.startswith("/api/") or status >= 400:
            ms = (time.perf_counter() - started) * 1000
            sys.stderr.write(f"{method:6} {path} -> {status} ({ms:.1f} ms)\n")

    def send_response(self, code, message=None):
        self._status = code
        super().send_response(code, message)

    def log_message(self, format, *args):  # noqa: A002 - base-class signature
        pass  # replaced by _log_request


def prepare_database(db_path, seed_path=seed.DEFAULT_SEED_PATH):
    """Creates/migrates the schema and syncs the question bank. Returns seed counts."""
    pathlib.Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    conn = db.connect(db_path)
    try:
        db.migrate(conn)
        return seed.seed(conn, seed.load_bank(seed_path))
    finally:
        conn.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description="Run Quiz Arena (API + web app).")
    # Defaults come from the environment first, so a host like Render can supply
    # PORT (and a writable QUIZ_DB path) without changing the start command.
    parser.add_argument("--host", default=os.environ.get("HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", "8000")))
    parser.add_argument(
        "--db",
        default=os.environ.get("QUIZ_DB", str(DEFAULT_DB_PATH)),
        help="SQLite database file (default: $QUIZ_DB, else data/quiz.db)",
    )
    parser.add_argument("--open", action="store_true", help="open the app in the default browser")
    parser.add_argument(
        "--test", action="store_true", help="use a throwaway database and serve the browser test suite at /tests/"
    )
    args = parser.parse_args(argv)
    sys.stdout.reconfigure(line_buffering=True)  # show the banner even when output is piped

    temp_dir = None
    db_path = args.db
    if args.test:
        temp_dir = tempfile.TemporaryDirectory(prefix="quiz-arena-test-")
        db_path = str(pathlib.Path(temp_dir.name) / "test.db")

    try:
        counts = prepare_database(db_path)
    except seed.SeedError as error:
        raise SystemExit(f"Could not load the question bank.\n{error}") from None

    server = QuizServer((args.host, args.port), db_path=db_path, test_mode=args.test)
    url = f"http://{args.host}:{server.server_address[1]}/"
    print(f"Quiz Arena running at {url}")
    print(f"  database: {'(temporary test database)' if args.test else db_path}")
    print(f"  questions: {counts['inserted']} added, {counts['updated']} updated, {counts['deactivated']} retired")
    if args.test:
        print(f"  browser tests: {url}tests/")
    print("  Ctrl+C to stop")
    if args.open:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")
    finally:
        server.server_close()
        if temp_dir:
            temp_dir.cleanup()


if __name__ == "__main__":
    main()
