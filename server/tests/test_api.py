"""End-to-end HTTP tests against a real server on an ephemeral port."""

import http.client
import json
import pathlib
import tempfile
import threading
import unittest

from server import app
from server.tests import helpers


class ApiTestCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.db_path = str(pathlib.Path(cls.tmp.name) / "api.db")
        app.prepare_database(cls.db_path)
        cls.clock = helpers.FakeClock()
        cls.server = app.QuizServer(("127.0.0.1", 0), db_path=cls.db_path, clock=cls.clock, quiet=True)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.port = cls.server.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.tmp.cleanup()

    def request(self, method, path, body=None, key=None, raw=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        all_headers = dict(headers or {})
        if key:
            all_headers["X-Player-Key"] = key
        payload = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        if payload is not None:
            all_headers["Content-Type"] = "application/json"
        conn.request(method, path, body=payload, headers=all_headers)
        response = conn.getresponse()
        data = response.read()
        conn.close()
        content_type = response.getheader("Content-Type", "")
        parsed = json.loads(data) if content_type.startswith("application/json") else data
        return response.status, parsed, response

    def new_player(self, name):
        status, body, _ = self.request("POST", "/api/players", {"name": name})
        self.assertEqual(status, 201, body)
        return body["key"]


class ApiTests(ApiTestCase):
    def test_health_and_catalog(self):
        status, body, response = self.request("GET", "/api/health")
        self.assertEqual((status, body["ok"]), (200, True))
        self.assertEqual(response.getheader("Cache-Control"), "no-store")
        self.assertIn("default-src 'self'", response.getheader("Content-Security-Policy"))

        status, catalog, _ = self.request("GET", "/api/catalog")
        self.assertEqual(status, 200)
        self.assertEqual(len(catalog["topics"]), 6)
        self.assertEqual(sum(sum(t["counts"].values()) for t in catalog["topics"]), 120)
        self.assertEqual(catalog["rules"]["difficultyPoints"]["hard"], 30)

    def test_full_quiz_over_http(self):
        key = self.new_player("Http Hero")
        status, state, _ = self.request(
            "POST", "/api/attempts", key=key,
            body={"topics": ["math"], "difficulty": "easy", "count": 3, "timerMode": "question",
                  "secondsPerQuestion": 20, "shuffle": True, "negativeMarking": False},
        )
        self.assertEqual(status, 201, state)
        self.assertNotIn("correctOptionId", json.dumps(state["current"]["question"]))
        for position in range(3):
            option = state["current"]["question"]["options"][0]["id"]
            self.clock.advance(1000)
            status, state, _ = self.request("POST", f"/api/attempts/{state['id']}/answer", {"position": position, "optionId": option}, key)
            self.assertEqual(status, 200, state)
            self.assertIsNotNone(state["current"]["correctOptionId"])
            status, state, _ = self.request("POST", f"/api/attempts/{state['id']}/next", {"position": position}, key)
        self.assertEqual(state["status"], "finished")

        status, results, _ = self.request("GET", f"/api/attempts/{state['id']}/results", key=key)
        self.assertEqual((status, results["total"], len(results["review"])), (200, 3, 3))

        status, history, _ = self.request("GET", "/api/me/history", key=key)
        self.assertEqual((status, history["stats"]["attempts"]), (200, 1))

        status, board, _ = self.request("GET", "/api/leaderboard?limit=5")
        self.assertEqual(status, 200)
        self.assertIsInstance(board["entries"], list)

    def test_auth_is_required_and_scoped(self):
        status, body, _ = self.request("GET", "/api/me/history")
        self.assertEqual((status, body["error"]["code"]), (401, "unauthorized"))
        status, body, _ = self.request("GET", "/api/me", key="made-up-key")
        self.assertEqual(status, 401)

        owner = self.new_player("Owner")
        intruder = self.new_player("Intruder")
        _, state, _ = self.request(
            "POST", "/api/attempts", key=owner,
            body={"topics": ["cs"], "difficulty": "mixed", "count": 2, "timerMode": "off",
                  "secondsPerQuestion": 30, "shuffle": False, "negativeMarking": False},
        )
        status, body, _ = self.request("GET", f"/api/attempts/{state['id']}", key=intruder)
        self.assertEqual(status, 404)

    def test_errors_are_json(self):
        key = self.new_player("Error Tester")
        cases = [
            ("POST", "/api/players", {"name": "error tester"}, None, 409),  # taken (case-insensitive)
            ("POST", "/api/attempts", {"topics": []}, key, 400),
            ("GET", "/api/nope", None, None, 404),
            ("PUT", "/api/catalog", None, None, 405),
            ("GET", "/api/leaderboard?limit=abc", None, None, 400),
            ("GET", "/api/attempts/does-not-exist", None, key, 404),
        ]
        for method, path, body, player_key, expected in cases:
            status, response_body, _ = self.request(method, path, body, player_key)
            self.assertEqual(status, expected, f"{method} {path}: {response_body}")
            self.assertIn("message", response_body["error"])

    def test_malformed_and_oversized_bodies(self):
        status, body, _ = self.request("POST", "/api/players", raw=b"{not json")
        self.assertEqual((status, body["error"]["code"]), (400, "invalid_input"))
        status, body, _ = self.request("POST", "/api/players", raw=b"x" * (app.MAX_BODY_BYTES + 1))
        self.assertEqual(status, 413)

    def test_static_files_are_allow_listed(self):
        status, body, response = self.request("GET", "/")
        self.assertEqual(status, 200)
        self.assertTrue(response.getheader("Content-Type").startswith("text/html"))
        status, _, response = self.request("GET", "/js/main.js")
        self.assertEqual((status, response.getheader("Content-Type")), (200, "text/javascript; charset=utf-8"))
        for path in ("/data/quiz.db", "/data/questions.json", "/server/app.py", "/.git/config",
                     "/js/../server/app.py", "/css/..%2f..%2fserver%2fapp.py", "/tests/index.html"):
            status, _, _ = self.request("GET", path)
            self.assertEqual(status, 404, path)


if __name__ == "__main__":
    unittest.main()
