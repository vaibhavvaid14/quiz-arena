# Kotlin port — what happened, and what to know

Quiz Arena was rewritten from Python + vanilla JavaScript to Kotlin, because the
project could not use the original stack. **The rewrite is finished, merged into
`main`, and deployed.** This file is kept as the record of how it was done and
which decisions should not be quietly undone.

Live: <https://quiz-arena-kotlin.onrender.com/>

## What the project is now

```
shared/    Rules.kt, Dto.kt          compiled for BOTH the JVM and JS
server/    Application, Routing, QuizService, Seeder, Database, Errors
client/    Api, Dom, Timer, Storage, Config, Components, Dialog, App,
           and the five screens (setup, quiz, results, history, leaderboard)
```

`shared` is the reason this is one language rather than two: the API's request
and response types are declared once and compiled into both ends, so renaming a
field breaks the build on both sides at once.

The Python implementation and the JavaScript frontend are gone from the working
tree; the history keeps them.

## Tests: 94

| Suite | Count | Command |
|---|---|---|
| Server | 65 | `./gradlew :server:test` |
| Client | 22 | `./gradlew :client:jsTest` (headless Chrome) |
| End-to-end | 7 | see below |

The end-to-end suite is the strongest evidence the port is faithful. It loads
the real page in an iframe and clicks through it, importing nothing from the
application. It was written against the *JavaScript* frontend and passes
unchanged against the Kotlin one, so it cannot have been bent to fit.

```bash
./gradlew :server:buildFatJar :client:assembleTestWeb
QUIZ_TEST=1 QUIZ_STATIC=client/build/web-test java -jar server/build/libs/server-all.jar
chrome --headless=new --virtual-time-budget=180000 --dump-dom http://127.0.0.1:8000/tests/
# expect PASS 7/7
```

## Deployment

Render has no JVM runtime, so the service is a **Docker** web service built from
the `Dockerfile`, which compiles the server jar and the Kotlin/JS bundle and
ships both. `render.yaml` describes it.

A service's runtime cannot be changed on Render, so the original Python service
could not be converted — the Docker service was created alongside it. If the old
`quiz-arena` service still exists it is dead weight: it auto-deploys `main`,
which no longer contains a Python app, so its builds fail.

Free plan: the disk is wiped on every deploy and the service sleeps after about
fifteen minutes idle, so the leaderboard and player history reset. Questions
re-seed on every boot. `render.yaml` has the commented-out disk and `QUIZ_DB`
settings that make scores permanent.

## Things that were got wrong once, and should not be got wrong again

A review of the port against the Python original found twelve behavioural
differences, all since fixed and covered by tests. The ones worth remembering:

- **`Rules.roundHalfUp` is deliberate.** Python's `round()` is banker's rounding
  and Kotlin's `Math.round(-2.5)` gives `-2`; scores want `2.5 -> 3` and
  `-2.5 -> -3`. Do not "simplify" it to a stdlib call. The same trap was walked
  into a second time in `formatDuration`, where `round()` made 500 ms render as
  `"0s"`.
- **Names are not ASCII.** `\s+` in Kotlin matches ASCII whitespace only, so an
  ideographic space slipped through as a valid name; and rejecting Unicode
  category `Cs` rejected every emoji, because on the JVM a non-BMP character *is*
  a surrogate pair. The length cap counts code points, not UTF-16 units.
- **Hold the lock across recovery.** `QuizScreen.act` released its `busy` flag
  before the 409 stale-tab reload, so a keypress could fire a second answer for a
  position the server had already passed.
- **`dialog.open` is `undefined` where `<dialog>` is unsupported**, so casting it
  to `Boolean` threw and stranded the coroutine — defeating the fallback three
  lines below it.
- **Names must match across canonical equivalence.** The JavaScript compared with
  `localeCompare` at accent sensitivity; a plain case-insensitive compare means a
  decomposed spelling misses the stored composed player, and the owner gets a 409
  for their own name.

## Other decisions worth not re-litigating

- **One SQLite connection behind a lock.** SQLite has a single writer anyway and
  `BEGIN IMMEDIATE` is easier to reason about this way. The lock is required
  because Ktor serves on many threads; nested transactions join the outer one
  rather than issuing a second `BEGIN`, which SQLite rejects.
- **The seeder validates raw JSON, not data classes.** Parsing into typed objects
  first would abort on the first bad question instead of reporting all of them.
- **The static traversal check decides on the resolved path**, so
  `js/../server/QuizService.kt` cannot pass by starting with an allowed prefix.
  There is deliberately no `/api/` catch-all route: one would turn a
  wrong-method request into 404 where Ktor otherwise answers 405.
- **`Api` passes serializers explicitly** rather than using reified inline
  functions: a public inline function may not touch private state, and both the
  player key and the `Json` instance need to stay private.
- **Client test names are camelCase.** Kotlin/JS rejects backticked identifiers
  with spaces, unlike the JVM.
- **`gradlew` is committed with its execute bit set.** Git on Windows does not
  record it, and without it CI fails with exit code 126.
- **`validateDistributionUrl=false`** only skips Gradle's reachability probe for
  its own distribution; the committed wrapper still points at the real URL.
- **The question bank lives only in `data/questions.json`** and is copied into
  the jar by `processResources`. A second copy under resources invited editing
  one and shipping the other.

## Known limits

- **The bundle is ~356 KB** before compression, against a much smaller
  hand-written JavaScript frontend. Kotlin/JS ships its stdlib, coroutines and
  the serialization runtime. That is the price of one language and a
  compile-time-checked contract.
- **No visual preview tool.** `tests/preview.html` rendered one screen at a time
  for visual checks by importing the JavaScript screens. It was deleted rather
  than left broken, and has no Kotlin equivalent yet.
- **Cold starts are fine, contrary to expectation.** This was predicted to be
  much worse than Python's; measured, the application starts in about 0.7 s and
  a cold request takes roughly 1.1 s end to end.
