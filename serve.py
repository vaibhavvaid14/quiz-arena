"""Starts Quiz Arena: the JSON API, the SQLite database and the web app.

Usage:
  python serve.py [--port 8000] [--open]      play (database: data/quiz.db)
  python serve.py --test                      throwaway database + browser tests at /tests/

Standard library only; see server/app.py for the API.
"""

from server.app import main

if __name__ == "__main__":
    main()
