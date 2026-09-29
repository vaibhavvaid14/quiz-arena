-- Quiz Arena database schema (migration 1).
-- Times are Unix epoch milliseconds (INTEGER); booleans are 0/1 with CHECKs.

-- ---------------------------------------------------------------- content

CREATE TABLE topics (
  id          TEXT PRIMARY KEY,                    -- slug, e.g. 'javascript'
  name        TEXT NOT NULL CHECK (length(trim(name)) > 0),
  icon        TEXT NOT NULL DEFAULT '',
  description TEXT NOT NULL DEFAULT '',
  position    INTEGER NOT NULL DEFAULT 0           -- display order
);

CREATE TABLE questions (
  id          TEXT PRIMARY KEY,                    -- e.g. 'js-h01'
  topic_id    TEXT NOT NULL REFERENCES topics (id),
  difficulty  TEXT NOT NULL CHECK (difficulty IN ('easy', 'medium', 'hard')),
  prompt      TEXT NOT NULL CHECK (length(trim(prompt)) > 0),
  code        TEXT,                                -- optional snippet shown under the prompt
  explanation TEXT NOT NULL CHECK (length(trim(explanation)) > 0),
  -- Questions are never hard-deleted once used: past attempts keep pointing at them.
  is_active   INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
  position    INTEGER NOT NULL DEFAULT 0,          -- order in the seed file
  updated_at  INTEGER NOT NULL
);

CREATE INDEX idx_questions_pick ON questions (topic_id, difficulty) WHERE is_active = 1;

CREATE TABLE question_options (
  id          INTEGER PRIMARY KEY,
  question_id TEXT NOT NULL REFERENCES questions (id) ON DELETE CASCADE,
  position    INTEGER NOT NULL CHECK (position >= 0),
  text        TEXT NOT NULL CHECK (length(trim(text)) > 0),
  is_correct  INTEGER NOT NULL DEFAULT 0 CHECK (is_correct IN (0, 1)),
  UNIQUE (question_id, position)
);

-- Exactly one correct option per question is enforced by the database itself
-- (at most one here; "at least one" is checked when a question is written).
CREATE UNIQUE INDEX uq_one_correct_option ON question_options (question_id) WHERE is_correct = 1;

-- ---------------------------------------------------------------- players

CREATE TABLE players (
  id          INTEGER PRIMARY KEY,
  name        TEXT NOT NULL UNIQUE COLLATE NOCASE CHECK (length(trim(name)) BETWEEN 1 AND 30),
  key_hash    TEXT NOT NULL UNIQUE,               -- sha256 of the player's secret key
  created_at  INTEGER NOT NULL
);

-- --------------------------------------------------------------- attempts

CREATE TABLE attempts (
  id                   TEXT PRIMARY KEY,           -- unguessable token, used in URLs
  player_id            INTEGER NOT NULL REFERENCES players (id) ON DELETE CASCADE,
  status               TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'finished')),
  end_reason           TEXT CHECK (end_reason IN ('completed', 'time-up', 'quit')),

  -- configuration snapshot
  topics               TEXT NOT NULL,              -- JSON array of topic ids
  difficulty           TEXT NOT NULL CHECK (difficulty IN ('mixed', 'easy', 'medium', 'hard')),
  timer_mode           TEXT NOT NULL CHECK (timer_mode IN ('question', 'session', 'off')),
  seconds_per_question INTEGER NOT NULL CHECK (seconds_per_question BETWEEN 5 AND 600),
  shuffle              INTEGER NOT NULL CHECK (shuffle IN (0, 1)),
  negative_marking     INTEGER NOT NULL CHECK (negative_marking IN (0, 1)),
  is_retry             INTEGER NOT NULL DEFAULT 0 CHECK (is_retry IN (0, 1)),
  question_count       INTEGER NOT NULL CHECK (question_count > 0),

  current_index        INTEGER NOT NULL DEFAULT 0,
  started_at           INTEGER NOT NULL,
  finished_at          INTEGER,

  -- summary written once when the attempt finishes (history + leaderboard read these)
  correct_count        INTEGER,
  total_points         INTEGER,
  max_points           INTEGER,
  percentage           INTEGER,
  duration_ms          INTEGER,

  CHECK ((status = 'finished') = (finished_at IS NOT NULL)),
  CHECK ((status = 'finished') = (end_reason IS NOT NULL))
);

CREATE INDEX idx_attempts_player ON attempts (player_id, finished_at DESC);
CREATE INDEX idx_attempts_ranked ON attempts (total_points DESC) WHERE status = 'finished';

CREATE TABLE attempt_questions (
  attempt_id         TEXT NOT NULL REFERENCES attempts (id) ON DELETE CASCADE,
  position           INTEGER NOT NULL CHECK (position >= 0),
  question_id        TEXT NOT NULL REFERENCES questions (id),
  option_order       TEXT NOT NULL,                -- JSON array of option ids, display order
  served_at          INTEGER,                      -- server time the question was shown
  answered_at        INTEGER,
  -- RESTRICT: an option a player chose can never be deleted out from under history.
  selected_option_id INTEGER REFERENCES question_options (id) ON DELETE RESTRICT,
  status             TEXT NOT NULL DEFAULT 'pending'
                       CHECK (status IN ('pending', 'correct', 'wrong', 'timeout', 'skipped')),
  time_spent_ms      INTEGER NOT NULL DEFAULT 0 CHECK (time_spent_ms >= 0),
  points             INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (attempt_id, position),
  UNIQUE (attempt_id, question_id)
);

CREATE INDEX idx_attempt_questions_question ON attempt_questions (question_id);
