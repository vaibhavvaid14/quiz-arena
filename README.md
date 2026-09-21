# Quiz Arena

A timed multiple-choice quiz app backed by a real database. Answers get
instant feedback and explanations, and there are score analytics, per-player
history and a shared leaderboard. 210 questions across 9 topics, from
JavaScript and Python to world history and film.

- **Backend:** Python 3.10+ standard library only (`http.server` + `sqlite3`).
  A JSON REST API with server-side scoring.
- **Frontend:** plain HTML, CSS and JavaScript (ES modules). No build step.
  Display type from Google Fonts, with a system-font fallback offline.
- **Nothing to install.** If Python runs, the app runs.

## Run it

```bash
python serve.py --open        # or double-click start.bat on Windows
```

Then open <http://127.0.0.1:8000/>. On first start the server creates
`data/quiz.db`, applies the schema and loads the 210 questions from
`data/questions.json`. Every later start syncs any edits you've made to that
file.

| Option | Meaning |
|---|---|
| `--port 8000` | port to listen on |
| `--db path/to/file.db` | use a different database file |
| `--test` | throwaway database, and serves the browser tests at `/tests/` |
| `--open` | open the browser |

## Test it

```bash
python -m unittest discover -s server/tests -t .     # 51 backend tests
python serve.py --test                                # then open http://127.0.0.1:8000/tests/
```

**Backend tests.** They cover:
- the schema and its constraints
- seeding
- every quiz rule, including timer expiry, using a fake clock
- leaderboard ranking
- the HTTP API itself: auth, error format, request limits, and static files
  (including path traversal)

**Browser tests (27).** They cover the API client, local storage, the timer,
and end-to-end flows. The flows drive the real UI against the real server:
play, resume, end early, keyboard play, name conflicts, countdown expiry, and
what happens when the server is offline.

The page title becomes `PASS n/n` or `FAIL n/n`, so it also works headless:

```bash
chrome --headless=new --virtual-time-budget=90000 --dump-dom http://127.0.0.1:8000/tests/
```

`tests/preview.html?screen=setup|quiz|feedback|results|history|leaderboard&theme=light|dark`
seeds the test database through the API and opens that screen, for visual
checks.

## Deploy it

The repo carries a [render.yaml](render.yaml) blueprint. In the Render
dashboard choose **New > Blueprint**, pick this repo and apply; Render reads the
file and builds the service. Nothing needs configuring by hand.

The start command is `python serve.py --host 0.0.0.0`. `serve.py` takes its
host, port and database path from `HOST`, `PORT` and `QUIZ_DB` when they are
set, which is how Render supplies the port it wants.

On the free plan the disk is wiped on every deploy and the service sleeps after
about fifteen minutes idle, so the first request after a nap takes ~30 seconds
and scores do not survive a restart. Questions re-seed from
`data/questions.json` on every boot, so the quiz itself always works.
`render.yaml` shows the disk and `QUIZ_DB` settings that make scores permanent.
## How it works

```
Browser (js/)                         Server (server/)                 SQLite (data/quiz.db)
─────────────                         ────────────────                 ─────────────────────
screens ── api.js ── fetch JSON ──►  app.py   routing, errors,  ──►   topics, questions,
timer (display only)                           static files             question_options,
storage (player key,                  service.py  quiz rules            players, attempts,
  resume id, prefs)                   rules.py    scoring constants     attempt_questions
                                      seed.py     questions.json → DB
                                      db.py       connections, migrations
```

**The server is in charge.** A quiz app that ships its answer key to the
browser can be beaten with devtools, so this one doesn't:

- **Answers stay secret until you answer.** The browser gets options without
  answers. The correct option and the explanation arrive only once that
  question is settled.
- **Server clock only.** Time is measured by the server, minus a 300 ms
  allowance for network delay, so the browser can't claim it answered faster
  than it did.
- **Closing the tab doesn't stop the clock.** Every request first settles any
  clock that ran out ("lazy expiry"). A tab closed mid-quiz still times out,
  and a whole-quiz countdown still submits the quiz.
- **Reading explanations is free.** A question's clock stops when you answer
  and the next one starts only when you ask for it.
- **Double-clicks and retries are harmless.** Answering twice or clicking
  "next" twice returns the current state instead of erroring. A stale tab gets
  a 409 and reloads the real state.

**Players.** There are no passwords. Using a name for the first time claims
it and returns a secret key, which the server stores only as a SHA-256 hash.
The browser keeps the key and sends it as `X-Player-Key`. Another browser
can't use that name, and attempts are private to their owner.

**Database design** ([server/schema.sql](server/schema.sql)):

- **Answer options are rows**, not a JSON list. A partial unique index
  guarantees at most one correct option per question.
- **History can't be rewritten.** `attempt_questions.selected_option_id` is a
  foreign key with `ON DELETE RESTRICT`, so an option someone chose can never
  disappear. The seeder also refuses to change the options of a question that
  has already been played.
- **Questions are never deleted.** Removing one from the JSON deactivates it,
  so old attempts stay reviewable.
- **Finished attempts store their score summary**, so history and the
  leaderboard are single indexed queries. The leaderboard uses window
  functions (`ROW_NUMBER() OVER (PARTITION BY player …)`).
- **Engine settings:** foreign keys on, WAL journal, `BEGIN IMMEDIATE` write
  transactions, and schema versioning with `PRAGMA user_version`.

## API

All responses are JSON. Errors look like `{"error": {"code", "message"}}`.
Routes marked 🔑 need the `X-Player-Key` header.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/catalog` | topics with question counts, plus scoring rules |
| POST | `/api/players` | claim a name → `{id, name, key}` (409 if taken) |
| GET | `/api/me` 🔑 | who this key belongs to |
| GET / DELETE | `/api/me/history` 🔑 | stats, topic mastery, attempts / clear them |
| POST | `/api/attempts` 🔑 | start a quiz (optional `questionIds` for "retry missed") |
| GET / DELETE | `/api/attempts/{id}` 🔑 | current state / discard an unfinished quiz |
| POST | `/api/attempts/{id}/answer` 🔑 | `{position, optionId}`; `optionId: null` = timed out |
| POST | `/api/attempts/{id}/next` 🔑 | `{position}` |
| POST | `/api/attempts/{id}/finish` 🔑 | end now (time-up or quit, decided by the server) |
| GET | `/api/attempts/{id}/results` 🔑 | summary and full review |
| GET | `/api/leaderboard?limit=20` | top players (a valid key also returns your own rank) |

## Topics

210 questions across 9 topics, each with an easy / medium / hard spread and a
written explanation. A test enforces the spread and keeps the correct answer
from clustering on one position.

| Topic | Questions | Covers |
|---|---|---|
| ⚡ JavaScript | 20 | Types, scope, closures, `this`, and the event loop |
| 🎨 HTML & CSS | 20 | Markup semantics, the box model, layout, and the cascade |
| 💻 Computer Science | 20 | Data structures, algorithms, complexity, networking, and OS basics |
| 🔬 General Science | 20 | Core facts from physics, chemistry, biology, and astronomy |
| 🌍 World Geography | 20 | Capitals, rivers, seas, and landforms around the globe |
| 🧮 Math & Logic | 20 | Arithmetic, algebra, geometry, probability, and reasoning puzzles |
| 🐍 Python | 30 | Syntax, data structures, comprehensions, generators and the object model |
| 🏛️ World History | 30 | Empires, revolutions, treaties and the turning points that shaped the world |
| 🎬 Movies & TV | 30 | Directors, classics, blockbusters and the small screen's biggest hits |
## Adding questions

Edit `data/questions.json`, then restart the server or run
`python -m server.seed`. Each question looks like this:

```json
{
  "id": "js-h07",
  "topic": "javascript",
  "difficulty": "hard",
  "question": "What does this log?",
  "code": "console.log(typeof null)",
  "options": ["\"null\"", "\"object\"", "\"undefined\"", "\"number\""],
  "answer": 1,
  "explanation": "A legacy quirk: typeof null is \"object\"."
}
```

The whole file is validated before anything is written, and one bad question
rejects the sync. To change the options of a question people have already
played, give it a new id; the old one is retired automatically.

## Scoring

| | Points |
|---|---|
| Correct answer | 10 easy · 20 medium · 30 hard |
| Speed bonus (per-question timer only) | up to +50% of base, in proportion to time left |
| Wrong answer | 0, or −25% of base with negative marking (total never below 0) |
| Timed out / skipped | 0 |

The percentage score is correct answers ÷ questions. Grades: A ≥ 90, B ≥ 75,
C ≥ 60, D ≥ 40, F below.

The **leaderboard** shows each player's single best finished quiz of at least
5 questions, ranked by points. Quizzes ended early and "retry missed" runs
don't count.

## Known limits

- **Names aren't password-protected.** A name belongs to the browser that
  claimed it; clearing site data loses access to it. Real accounts would need
  sign-in (see the suggestions in the project notes).
- **Single machine only.** SQLite with one server process suits a class or a
  team. For many simultaneous users, move to PostgreSQL behind a production
  WSGI server.
- **Tested in Chrome.** The app uses ES modules, `<dialog>`, CSS `:has()`,
  `color-mix()` and `backdrop-filter`, all supported by current Chrome, Edge,
  Firefox and Safari.
- **Fonts come from Google Fonts.** Offline, or anywhere that host is blocked,
  the UI falls back to system fonts and everything still works. The
  Content-Security-Policy in `server/app.py` allows exactly those two font
  hosts and nothing else.
