"""Storage backends for Sleep Monitor.

The configured STORAGE_SERVICE is the source used by /save and /sync.
The other backend remains available for the monthly reconciliation.
"""

from __future__ import annotations

import datetime
import logging
import os
import sqlite3
from pathlib import Path
from typing import Callable

from app import obsidian
from app.obsidian import ObsidianFetchError

logger = logging.getLogger("sleepmon.storage")

STORAGE_SERVICE = os.getenv("STORAGE_SERVICE", "obsidian").strip().lower()
SQLITE_PATH = os.getenv("SLEEPMON_SQLITE_PATH", "/data/sleepmon.db")

if STORAGE_SERVICE not in {"sqlite", "obsidian"}:
    raise ValueError("STORAGE_SERVICE must be either 'sqlite' or 'obsidian'")


def _db() -> sqlite3.Connection:
    path = Path(SQLITE_PATH)
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path, timeout=30)
    connection.row_factory = sqlite3.Row
    connection.execute(
        """
        CREATE TABLE IF NOT EXISTS notes (
            date TEXT PRIMARY KEY,
            content TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """
    )
    connection.execute(
        """
        CREATE TABLE IF NOT EXISTS sync_runs (
            month TEXT PRIMARY KEY,
            completed_at TEXT NOT NULL
        )
        """
    )
    connection.commit()
    return connection


def sqlite_get(date_str: str) -> str | None:
    with _db() as db:
        row = db.execute("SELECT content FROM notes WHERE date = ?", (date_str,)).fetchone()
        return row["content"] if row else None


def sqlite_save(date_str: str, content: str) -> bool:
    now = datetime.datetime.now(datetime.timezone.utc).isoformat()
    with _db() as db:
        db.execute(
            """
            INSERT INTO notes(date, content, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(date) DO UPDATE SET
                content = excluded.content,
                updated_at = excluded.updated_at
            """,
            (date_str, content, now),
        )
        db.commit()
    return True


def _backend(service: str) -> tuple[Callable[[str], str | None], Callable[[str, str], bool]]:
    if service == "sqlite":
        return sqlite_get, sqlite_save
    return obsidian.get_note_content, obsidian.save_note_content


def get_note_content(date_str: str) -> str | None:
    getter, _ = _backend(STORAGE_SERVICE)
    return getter(date_str)


def save_note_content(date_str: str, content: str) -> bool:
    _, saver = _backend(STORAGE_SERVICE)
    return saver(date_str, content)


def _other_service() -> str:
    return "sqlite" if STORAGE_SERVICE == "obsidian" else "obsidian"


def _month_dates(month: str) -> list[str]:
    first = datetime.date.fromisoformat(f"{month}-01")
    if first.month == 12:
        next_month = datetime.date(first.year + 1, 1, 1)
    else:
        next_month = datetime.date(first.year, first.month + 1, 1)
    days = (next_month - first).days
    return [(first + datetime.timedelta(days=i)).isoformat() for i in range(days)]


def _previous_month(today: datetime.date | None = None) -> str:
    today = today or datetime.date.today()
    first = today.replace(day=1)
    previous = first - datetime.timedelta(days=1)
    return previous.strftime("%Y-%m")


def _sync_run_exists(month: str) -> bool:
    with _db() as db:
        return db.execute(
            "SELECT 1 FROM sync_runs WHERE month = ?", (month,)
        ).fetchone() is not None


def _mark_sync_run(month: str) -> None:
    now = datetime.datetime.now(datetime.timezone.utc).isoformat()
    with _db() as db:
        db.execute(
            """
            INSERT INTO sync_runs(month, completed_at)
            VALUES (?, ?)
            ON CONFLICT(month) DO UPDATE SET completed_at = excluded.completed_at
            """,
            (month, now),
        )
        db.commit()


def sync_month(month: str, force: bool = False) -> dict:
    """Reconcile one calendar month between the two storage backends.

    STORAGE_SERVICE wins when both backends contain a note. If only one side
    contains a note, it is copied to the other side. This makes switching the
    configured backend safe and also bootstraps SQLite from an existing vault.
    """
    if not force and _sync_run_exists(month):
        return {"month": month, "status": "already_synced", "copied": 0, "missing": 0}

    primary_get, primary_save = _backend(STORAGE_SERVICE)
    secondary_name = _other_service()
    secondary_get, secondary_save = _backend(secondary_name)

    copied = 0
    missing = 0

    for date_str in _month_dates(month):
        primary = primary_get(date_str)
        secondary = secondary_get(date_str)

        if primary is not None:
            if secondary != primary:
                if not secondary_save(date_str, primary):
                    raise RuntimeError(
                        f"Failed to write {date_str} to {secondary_name}"
                    )
                copied += 1
        elif secondary is not None:
            if not primary_save(date_str, secondary):
                raise RuntimeError(
                    f"Failed to bootstrap {date_str} into {STORAGE_SERVICE}"
                )
            copied += 1
        else:
            missing += 1

    _mark_sync_run(month)
    result = {
        "month": month,
        "status": "synced",
        "source": STORAGE_SERVICE,
        "target": secondary_name,
        "copied": copied,
        "missing": missing,
    }
    logger.info("Monthly storage sync completed: %s", result)
    return result


def sync_previous_month(force: bool = False) -> dict:
    return sync_month(_previous_month(), force=force)


def startup_sync() -> None:
    """Synchronize the previous month once after startup when needed."""
    try:
        sync_previous_month()
    except Exception:
        logger.exception("Monthly storage sync failed")
