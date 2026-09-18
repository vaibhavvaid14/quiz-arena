"""SQLite connection handling, transactions and schema migrations."""

import contextlib
import pathlib
import sqlite3

SCHEMA_DIR = pathlib.Path(__file__).resolve().parent

# Ordered migrations. The database's PRAGMA user_version records how many have
# been applied, so upgrading an existing quiz.db only runs the new ones.
MIGRATIONS = [
    (SCHEMA_DIR / "schema.sql").read_text(encoding="utf-8"),
]


def connect(path):
    """Opens a connection configured for this app.

    - foreign keys enforced (SQLite leaves them off by default)
    - WAL journal: readers never block the single writer
    - autocommit mode; writes are grouped explicitly with `transaction()`
    """
    conn = sqlite3.connect(str(path), timeout=5.0, isolation_level=None, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA busy_timeout = 5000")
    if str(path) != ":memory:":
        conn.execute("PRAGMA journal_mode = WAL")
        conn.execute("PRAGMA synchronous = NORMAL")
    return conn


@contextlib.contextmanager
def transaction(conn):
    """BEGIN IMMEDIATE ... COMMIT, rolled back on any exception.

    IMMEDIATE takes the write lock up front, so two requests racing on the same
    attempt (e.g. a double-clicked answer) are serialised instead of both
    reading stale state.
    """
    conn.execute("BEGIN IMMEDIATE")
    try:
        yield conn
    except BaseException:
        conn.execute("ROLLBACK")
        raise
    else:
        conn.execute("COMMIT")


def migrate(conn):
    """Applies pending migrations. Returns the resulting schema version."""
    version = conn.execute("PRAGMA user_version").fetchone()[0]
    for index, script in enumerate(MIGRATIONS[version:], start=version + 1):
        # executescript() issues its own COMMIT, so run it inside an explicit
        # script-level transaction to keep each migration atomic.
        try:
            conn.executescript(f"BEGIN;\n{script}\nPRAGMA user_version = {index};\nCOMMIT;")
        except sqlite3.Error:
            if conn.in_transaction:
                conn.execute("ROLLBACK")
            raise
    return conn.execute("PRAGMA user_version").fetchone()[0]
