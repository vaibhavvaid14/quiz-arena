# Kotlin port — state and how to resume

Quiz Arena was rewritten from Python + vanilla JS to Kotlin, because the project
may not use the original stack. The work lives on the `kotlin` branch. `main`
still holds the Python app and is what the live Render service runs, so nothing
here has touched the deployment.

**The rewrite is complete and verified. It has not been deployed or merged.**

## State

| Stage | State |
|---|---|
| 1. Toolchain, Gradle, `shared` module | done |
| 2. Ktor backend + tests | done |
| 3. Kotlin/JS frontend + tests | done |
| CI | green, including the Docker build |
| 4. Deploy to Render | **not done — needs the repo owner** |
| 5. Merge to `main`, retire the Python tree | **not done — blocked on the deploy** |

```
shared/    Rules.kt, Dto.kt          compiled for BOTH the JVM and JS
server/    Application, Routing, QuizService, Seeder, Database, Errors
client/    Api, Dom, Timer, Storage, Config, Components, Dialog, App,
           and the five screens (setup, quiz, results, history, leaderboard)
```

The old JavaScript frontend is gone. The Python tree is still present but has no
frontend on this branch, so `python serve.py` here would serve nothing — that is
only true on `kotlin`, and it goes away at merge.

## Tests: 82, all green on a clean Linux CI runner

| Suite | Count | Command |
|---|---|---|
| Server | 60 | `./gradlew :server:test` |
| Client | 15 | `./gradlew :client:jsTest` (headless Chrome) |
| End-to-end | 7 | see below |

The end-to-end suite is the strongest evidence the port is faithful. It loads
the real page in an iframe and clicks through it, importing nothing from the
application. It was written against the *JavaScript* frontend and passes
unchanged against the Kotlin one, so it cannot have been adjusted to fit.

```bash
./gradlew :server:buildFatJar :client:assembleTestWeb
QUIZ_TEST=1 QUIZ_STATIC=client/build/web-test java -jar server/build/libs/server-all.jar
chrome --headless=new --virtual-time-budget=180000 --dump-dom http://127.0.0.1:8000/tests/
# expect PASS 7/7
```

## Resuming

```bash
./gradlew :server:run      # builds the bundle and serves it on :8000
./gradlew :server:test :client:jsTest
```

JDK 21 is required; Gradle comes from the wrapper.

### The one thing that must happen in the right order

The live Render service is a **Python** web service wired to `main`. Changing
`render.yaml` does not retype an existing service, so **merging Kotlin into
`main` before the deploy is re-pointed would break the live site**: Render would
auto-deploy a repo with no Python app in it.

So:

1. **Re-point the deploy first.** In Render, either create a new Blueprint
   service from the `kotlin` branch (safest — you see it working before anything
   changes), or delete the existing service and re-apply the blueprint. Either
   way it needs the account owner.
2. **Then merge**, retiring the Python tree in the merge commit: `serve.py`,
   `server/*.py`, `server/tests/`, `requirements.txt`, `start.bat`, and
   `.github/workflows/tests.yml`.

An attempt to delete the Python tree earlier was refused by the sandbox as
irreversible local destruction, which is why it is still here. The owner has
asked for it to go at merge.

## Known gaps

- **The Docker deploy has never actually run on Render.** CI proves the image
  builds; nothing has proved it boots and serves there.
- **No visual preview tool.** `tests/preview.html` and `preview.js` rendered one
  screen at a time for visual checks by importing the JavaScript screens. They
  were deleted rather than left broken; the Kotlin app has no equivalent.
- **The bundle is ~356 KB** before compression, against a much smaller
  hand-written JavaScript frontend. Kotlin/JS ships its stdlib, coroutines and
  the serialization runtime. Expect questions about this.
- **Cold starts will be worse than the Python version's.** The container has to
  start and then the JVM has to boot, on top of the free plan's sleep.

## Decisions worth not re-litigating

- **`Rules.roundHalfUp` is deliberate.** Python's `round()` is banker's rounding
  and Kotlin's `Math.round(-2.5)` gives `-2`; scores want `2.5 -> 3` and
  `-2.5 -> -3`. Do not "simplify" it to a stdlib call.
- **One SQLite connection behind a lock.** SQLite has one writer anyway and
  `BEGIN IMMEDIATE` is easier to reason about this way. Ktor serves on many
  threads, so the lock is required; nested transactions join the outer one
  rather than issuing a second `BEGIN`, which SQLite rejects.
- **The seeder validates raw JSON, not data classes.** Parsing into typed
  objects first would abort on the first bad question instead of reporting all
  of them at once.
- **The static traversal check decides on the resolved path**, so
  `js/../server/QuizService.kt` cannot pass by starting with an allowed prefix.
- **`Api` passes serializers explicitly** rather than using reified inline
  functions: a public inline function may not touch private state, and both the
  player key and the `Json` instance need to stay private.
- **Client test names are camelCase.** Kotlin/JS rejects backticked identifiers
  with spaces, unlike the JVM.
- **`validateDistributionUrl=false`** in the root build only skips Gradle's
  reachability probe for its own distribution; the committed wrapper still
  points at the real URL. `gradlew` is committed with its execute bit set —
  without it, CI fails with exit code 126.
