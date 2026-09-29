# Kotlin port — state and how to resume

Quiz Arena is being rewritten from Python + vanilla JS to Kotlin, because the
project may not use the original stack. Work happens on the `kotlin` branch;
`main` still holds the Python app and is what the live Render service runs, so
nothing here has broken the deployment.

**Paused after stage 2.** The backend is done and passing; the frontend is not
started.

## Where things stand

| Stage | State |
|---|---|
| 1. Toolchain, Gradle, `shared` module | done |
| 2. Ktor backend + tests | done — 60 tests passing |
| 3. Kotlin/JS frontend | **not started** |
| 4. Docker + Render deploy | not started |

### Done

```
shared/    Rules.kt, Dto.kt          compiled for BOTH the JVM and JS
server/    Application.kt  entry point, env config
           Routing.kt      routes, CSP, error envelope, static allowlist
           QuizService.kt  the whole of the old service.py
           Seeder.kt       bank validation + idempotent sync
           Database.kt     SQLite/JDBC, transactions, migrations
           Errors.kt       domain errors -> HTTP
client/    (declared in settings.gradle.kts, no sources yet)
```

`schema.sql` and `questions.json` were carried over unchanged and live in
`server/src/main/resources/`.

### Two independent checks that the port is faithful

1. **60 Kotlin tests pass** (`ApiTest`, `ServiceTest`, `DatabaseTest`,
   `RulesTest`). The Python suite had 51.
2. **The 27 original JavaScript browser tests pass unmodified** against the
   Kotlin server. They only speak HTTP, so they never learned the backend
   changed language — which makes them a check that could not have been bent to
   fit the new code.

## Resuming

### Build and test

```bash
./gradlew :server:test          # 60 tests
./gradlew :shared:compileKotlinJvm :shared:compileKotlinJs
```

### Run it, and re-run the original browser suite against it

```bash
QUIZ_TEST=1 PORT=8300 QUIZ_STATIC=. ./gradlew :server:run
# then, in another shell:
chrome --headless=new --virtual-time-budget=150000 \
       --dump-dom http://127.0.0.1:8300/tests/      # expect PASS 27/27
```

Environment: `HOST`, `PORT`, `QUIZ_DB`, `QUIZ_STATIC`, `QUIZ_TEST`.

### Next steps, in order

1. **CI for the Kotlin build.** A workflow that runs `./gradlew :server:test` on
   a clean checkout. This also settles the one thing never verified locally —
   see "Known gaps" below.
2. **Stage 3, the Kotlin/JS frontend.** Port `js/**` (~3,700 lines) to
   `client/src/jsMain`. `css/styles.css` can be reused as-is.
3. **Stage 4, deploy.** Render has no JVM runtime, so `render.yaml` switches
   from `runtime: python` to Docker, with a multi-stage build
   (`./gradlew :server:buildFatJar`, then a JRE base image). Expect cold starts
   to get worse on the free plan: JVM boot lands on top of the 15-minute sleep.
4. **Merge to `main`** and retire the Python tree once stage 3 is verified.

### Open question, worth settling before stage 3

Whether the frontend really has to be Kotlin. Stage 3 is more than half the
remaining work, and "use Kotlin" most often means the backend or an Android app.
If the backend alone satisfies the requirement, the existing JS frontend is
better kept than replaced: it is what independently verifies the Kotlin server.

## Known gaps

- **A clean-checkout `./gradlew build` has never been verified.** The wrapper
  downloads Gradle on first run, and the machine this was built on could not
  reach `services.gradle.org`'s CDN from the JVM (curl could). The local cache
  was seeded by hand instead. CI on a fresh runner is what will actually prove
  it — step 1 above.
- `validateDistributionUrl=false` in the root build is only there to skip that
  reachability probe. The committed wrapper still points at the real URL.
- The Python tree is still present on this branch; nothing removes it yet.

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
