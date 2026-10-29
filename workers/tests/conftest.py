import os
from pathlib import Path

import psycopg
import pytest

MIGRATIONS = Path(__file__).resolve().parents[2] / "api" / "src" / "main" / "resources" / "db" / "migration"
DSN = os.environ.get("TEST_DATABASE_URL", "postgresql://beatmind:beatmind@localhost:5432/beatmind_test")
SCHEMA = "workers_test"


@pytest.fixture
def conn():
    """A connection to a fresh schema built from the API's Flyway migrations (one source of truth)."""
    try:
        c = psycopg.connect(DSN, options=f"-c search_path={SCHEMA}")
    except psycopg.OperationalError as exc:
        pytest.skip(f"PostgreSQL not reachable at TEST_DATABASE_URL: {exc}")
    c.execute(f"DROP SCHEMA IF EXISTS {SCHEMA} CASCADE")
    c.execute(f"CREATE SCHEMA {SCHEMA}")
    for path in sorted(MIGRATIONS.glob("V*__*.sql"), key=lambda p: int(p.name[1:].split("__")[0])):
        c.execute(path.read_text())
    c.commit()
    yield c
    c.rollback()
    c.execute(f"DROP SCHEMA IF EXISTS {SCHEMA} CASCADE")
    c.commit()
    c.close()
