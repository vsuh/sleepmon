#!/usr/bin/env python3
"""Diagnostic probe for Xiaomi Mi Fitness Cloud sleep data.

Credentials are read only from the local .env file. The script intentionally
does not print the passToken or write it to the output JSON.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
from datetime import date, timedelta
from pathlib import Path

from dotenv import load_dotenv
from mi_fitness_mcp.adapters.mi_fitness_cloud import MiFitnessCloudAdapter


BASE_DIR = Path(__file__).resolve().parent
load_dotenv(BASE_DIR / ".env")


def default_dates() -> tuple[str, str]:
    start = date(2026, 10, 6)
    return start.isoformat(), (start + timedelta(days=1)).isoformat()


async def main() -> None:
    parser = argparse.ArgumentParser(description="Probe Xiaomi Mi Fitness Cloud sleep data")
    default_start, default_end = default_dates()
    parser.add_argument("--start", default=default_start, help="Start date, YYYY-MM-DD")
    parser.add_argument("--end", default=default_end, help="End date, YYYY-MM-DD (exclusive)")
    args = parser.parse_args()

    user_id = os.getenv("XIAOMI_USER_ID")
    pass_token = os.getenv("XIAOMI_PASS_TOKEN")
    region = os.getenv("XIAOMI_REGION", "ru")

    if not user_id or not pass_token:
        raise SystemExit(
            "Set XIAOMI_USER_ID and XIAOMI_PASS_TOKEN in tools/xiaomi_cloud_probe/.env"
        )

    adapter = MiFitnessCloudAdapter(
        user_id=user_id,
        pass_token=pass_token,
        region=region,
    )

    output = {
        "start": args.start,
        "end": args.end,
        "region": region,
        "sessions": [],
        "raw_records": [],
    }

    await adapter.connect()
    try:
        # Keep the raw cloud response as a diagnostic artifact, but never
        # serialize credentials or the adapter itself.
        try:
            raw = await adapter._fetch_key("sleep", args.start, args.end)
            output["raw_records"] = raw if isinstance(raw, list) else [raw]
        except Exception as exc:
            output["raw_fetch_error"] = f"{type(exc).__name__}: {exc}"

        try:
            async for session in adapter.iter_sleep_sessions(args.start, args.end):
                if hasattr(session, "model_dump"):
                    item = session.model_dump(mode="json")
                elif hasattr(session, "dict"):
                    item = session.dict()
                else:
                    item = vars(session)
                output["sessions"].append(item)
        except Exception as exc:
            output["session_fetch_error"] = f"{type(exc).__name__}: {exc}"
    finally:
        close = getattr(adapter, "close", None)
        if close is not None:
            result = close()
            if hasattr(result, "__await__"):
                await result

    out_path = BASE_DIR / "sleep_cloud.json"
    out_path.write_text(json.dumps(output, ensure_ascii=False, indent=2, default=str), encoding="utf-8")

    print(f"Cloud probe finished: {len(output['sessions'])} parsed sleep session(s)")
    print(f"Saved diagnostic result to {out_path}")


if __name__ == "__main__":
    asyncio.run(main())
