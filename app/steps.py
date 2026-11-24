"""Pure helpers for merging step counters in /sync."""

from __future__ import annotations


def merge_steps_total(incoming: int | None, existing: int | str | None) -> int | str:
    """Return the steps_total value that /sync must store.

    A positive incoming value is the newest reading (steps accumulate during
    the day). 0 or missing means "no step data right now" and must never erase
    an already recorded value (same rule as pulse_avg_day).
    """
    if incoming is not None and incoming > 0:
        return incoming
    return existing if existing is not None else 0
