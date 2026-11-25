"""Human-readable date labels for the web UI."""

from __future__ import annotations

import datetime

WEEKDAYS = ("пн", "вт", "ср", "чт", "пт", "сб", "вс")
MONTHS = ("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")


def format_date_label(date_str: str) -> str:
    """Format YYYY-MM-DD as "ww. DD MMM", e.g. 2026-11-24 -> "вт. 24 ноя".

    An unparsable value is returned unchanged, so a bad ?date= never breaks the page.
    """
    try:
        day = datetime.date.fromisoformat(date_str)
    except (TypeError, ValueError):
        return date_str
    return f"{WEEKDAYS[day.weekday()]}. {day.day:02d} {MONTHS[day.month - 1]}"
