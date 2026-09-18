# Quiz Arena

A timed multiple-choice quiz app with instant feedback, explanations, score
analytics and saved history. Plain HTML, CSS and JavaScript (ES modules): no
framework, no build step, no dependencies.

## Run it

```bash
python serve.py --open        # or double-click start.bat on Windows
```

Then open <http://127.0.0.1:8000/>. Any static server works. `serve.py` exists
because browsers block ES modules over `file://`, and Python's stock server on
Windows often sends `.js` files with the wrong MIME type.

## Test it

With the server running, open <http://127.0.0.1:8000/tests/>. There are 44
tests: engine, timer, storage, question-bank validation, analytics, and
end-to-end flows that drive the real UI. The page title changes to
`PASS n/n` or `FAIL n/n`, so it also works in a headless browser:

```bash
chrome --headless=new --virtual-time-budget=60000 --dump-dom http://127.0.0.1:8000/tests/
```

`tests/preview.html?screen=setup|quiz|feedback|results|history&theme=light|dark`
puts the real app into any state with sample data, for visual checks.

## Features

- **Setup:** player name, topics (6 × 12 questions), difficulty
  (mixed/easy/medium/hard), length, timer mode, shuffle and negative marking.
  The last setup is remembered.
- **Quiz:** a question card with instant green/red feedback and an
  explanation, a live ring timer, running points, streak counter, a progress
  bar, and keyboard play (`1`–`4`/`A`–`D` to answer, `→`/`N` for next).
- **Timer modes**
  - *Per question:* when time runs out the question counts as timed out and
    the correct answer is shown. Fast answers earn up to 50% bonus points.
  - *Whole quiz:* one countdown. At zero the quiz is submitted automatically.
  - *Off:* untimed. Time is still recorded.
- **Results:** score ring, grade, stat tiles, accuracy by topic and by
  difficulty, and a filterable review of every question. Actions: retake,
  retry only the missed questions, or start a new quiz.
- **History:** lifetime stats, topic mastery across attempts, and a log of
  past attempts; each one can be reopened for review.
- **Persistence:** an unfinished quiz is saved after every answer and when the
  tab is hidden. It survives a reload and can be resumed. Storage is
  versioned, and corrupt or blocked storage falls back safely.
- **Accessibility:** keyboard play, focus moves to each new question, live
  announcements, status shown with icon and label (never by colour alone),
  reduced-motion support, and light/dark themes (follows the OS, with a
  manual toggle).

## Architecture

```
index.html              page shell (header, <main id="app">)
css/styles.css          design tokens (light + dark) and all styles
js/main.js              entry: validate bank -> pick storage -> mount app
js/app.js               controller: screen routing, start/complete quiz, theme
js/core/                framework-free logic, unit-tested
  config.js             every tunable number (points, bonus, limits)
  quizEngine.js         session state machine, scoring, results summary
  timer.js              drift-free countdown/stopwatch (injectable clock)
  storage.js            versioned localStorage repository with fallback
  questionBank.js       schema validation, indexing, filtering
  stats.js              history entries and cross-attempt analytics
  random.js             seeded RNG + Fisher–Yates shuffle
js/ui/                  DOM layer
  dom.js                safe element builder (text only, never innerHTML)
  components.js         timer ring, score ring, bars, tiles, pills
  dialog.js             accessible confirm dialog (<dialog>)
  screens/              setup, quiz, results, history
js/data/questions.js    the question bank
tests/                  in-browser test runner + suites + preview harness
```

**Design choices**

- The engine is **pure and immutable**. A session is a plain JSON object, and
  every action (`answerCurrent`, `timeoutCurrent`, `goToNext`,
  `finishSession`) returns a new one. That makes saving and resuming trivial
  and the logic testable without a DOM.
- **Time is measured, not counted.** The timer derives elapsed time from
  `performance.now()`, so throttled background tabs can't slow it down. Timers
  pause while feedback is shown, so reading an explanation costs nothing.
- **Dependencies are injected.** The app takes its storage, RNG and clock as
  arguments, so tests mount the whole UI on in-memory storage with a seeded
  RNG.
- **Safe rendering.** All content goes through text nodes, so a question like
  "What does `<section>` mean?" can never inject markup.

## Question schema

```js
{
  id: 'js-h01',                 // unique
  topic: 'javascript',          // must match a TOPICS id
  difficulty: 'hard',           // 'easy' | 'medium' | 'hard'
  question: 'What does this log?',
  code: 'console.log(1)',       // optional snippet shown under the question
  options: ['A', 'B', 'C', 'D'],// 2–6 unique strings
  answer: 2,                    // index into options
  explanation: 'Why the answer is right.',
}
```

To add questions or topics, edit `js/data/questions.js`. The bank is
validated at startup: invalid entries are skipped and logged in the console,
and the `bank:` tests fail until they're fixed.

## Scoring

| | Points |
|---|---|
| Correct answer | 10 easy · 20 medium · 30 hard |
| Speed bonus (per-question timer only) | up to +50% of base, in proportion to time left |
| Wrong answer | 0, or −25% of base with negative marking (total never below 0) |
| Timed out / skipped | 0 |

The percentage score is correct answers ÷ total questions. Grades: A ≥ 90,
B ≥ 75, C ≥ 60, D ≥ 40, F below that.

## Browser support

Tested in Chrome. The app uses ES modules, `<dialog>`, CSS `:has()` and
`color-mix()`, all of which current Chrome, Edge, Firefox and Safari support.
