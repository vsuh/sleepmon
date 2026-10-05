from fastapi import FastAPI, Request, Form, HTTPException, status, BackgroundTasks
from fastapi.responses import HTMLResponse, RedirectResponse, JSONResponse, Response
from fastapi.templating import Jinja2Templates
from fastapi.staticfiles import StaticFiles
import datetime
import yaml
import os
import asyncio
import logging
import time
import base64

from app.config import APP_PIN
from app import storage
from app.obsidian import ObsidianFetchError

# ---------- Logging ----------
# `docker compose logs -t` timestamps are Docker's own daemon-level log
# timestamps — always UTC, regardless of the container's TZ env var. TZ only
# affects processes running *inside* the container. So our own log lines
# carry their own local-time prefix here (respecting TZ=Europe/Moscow from
# docker-compose.yml), and `docker compose logs -f app` (WITHOUT -t) is the
# right way to read them — the app-printed timestamp is already correct,
# Docker's own -t prefix would only add a second, UTC, misleading one.
logging.Formatter.converter = time.localtime  # use local (TZ-aware) time, not UTC, for %(asctime)s
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)

# httpx emits request lines at INFO, but successful REST requests are routine
# diagnostics. Keep them available at DEBUG without lowering the global log
# level for the rest of the application.
class _HttpxRequestDebugFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        if record.name == "httpx" and record.levelno == logging.INFO and str(record.msg).startswith("HTTP Request:"):
            record.levelno = logging.DEBUG
            record.levelname = "DEBUG"
        return True


httpx_logger = logging.getLogger("httpx")
httpx_logger.setLevel(logging.DEBUG)
for _handler in logging.getLogger().handlers:
    _handler.addFilter(_HttpxRequestDebugFilter())

logger = logging.getLogger("sleepmon")

NOTE_CACHE: dict[str, str] = {}

def get_note_cached(date_str: str) -> str | None:
    """Cached read. Used ONLY for UI display (index form), where a slightly
    stale value is harmless and speed matters. Propagates ObsidianFetchError
    to the caller (does NOT swallow it).

    NEVER use this for a merge-write (see /sync) — the cache has no
    invalidation for edits made directly in the active backend or by other workers,
    so a merge based on cached "existing" data can silently overwrite a
    real value (e.g. well_being) with a stale one."""
    if date_str in NOTE_CACHE:
        return NOTE_CACHE[date_str]
    content = storage.get_note_content(date_str)
    if content:
        NOTE_CACHE[date_str] = content
    return content

def set_note_cache(date_str: str, content: str):
    NOTE_CACHE[date_str] = content


def build_related_link(date_str: str) -> str:
    """Build the `related` frontmatter link for a note, based on its date.

    Points at the monthly index note for that note's year/month:
    [[55-sleepmon/<YYYY>/index-<MM>.md]] — e.g. for 2026-08-26 that's
    [[55-sleepmon/2026/index-08.md]]. Month is zero-padded to 2 digits.
    """
    year, month, _day = date_str.split("-")
    return f"[[55-sleepmon/{year}/index-{month}.md]]"


def parse_note(content: str | None) -> dict:
    """Extract the fields we need to preserve/merge on a /sync write.

    Defaults assume a brand-new note (nothing recorded yet):
    well_being defaults to 9 for a brand-new note, no alcohol flag, no free-text notes, and the
    "fill-once" numeric fields (sleep_hours/sleep phases) at 0.
    """
    result = {
        "well_being": 9,
        "sleep_quality": 9,
        "alco": False,
        "notes": "",
        "sleep_hours": 0,
        "steps_total": 0,
        "sleep_awakenings": 0,
        "pulse_avg_sleep": 0,
        "pulse_avg_day": 0,
    }
    if content and content.startswith("---"):
        parts = content.split("---", 2)
        if len(parts) >= 3:
            frontmatter = yaml.safe_load(parts[1]) or {}
            result["well_being"] = frontmatter.get("well_being", 9)
            result["sleep_quality"] = frontmatter.get("sleep_quality", 9)
            result["alco"] = frontmatter.get("alco", False)
            result["notes"] = parts[2].replace("## Заметки\n\n", "").strip()
            result["sleep_hours"] = frontmatter.get("sleep_hours", 0)
            result["steps_total"] = frontmatter.get("steps_total", frontmatter.get("steps_1", 0) + frontmatter.get("steps_2", 0))
            result["sleep_awakenings"] = frontmatter.get("sleep_awakenings", 0)
            result["pulse_avg_sleep"] = frontmatter.get("pulse_avg_sleep", 0)
            result["pulse_avg_day"] = frontmatter.get("pulse_avg_day", 0)
    return result


app = FastAPI()
HOURLY_SYNC_TASK: asyncio.Task | None = None


async def _hourly_storage_sync_loop():
    retry_delay = 60
    hourly_delay = 60 * 60

    while True:
        try:
            await asyncio.to_thread(storage.sync_recent_months)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            # A dependent backend (for example Obsidian REST API) may still be
            # starting or may be temporarily unavailable. Keep the app alive
            # and retry soon instead of turning a transient outage into a
            # noisy one-hour failure window.
            logger.warning(
                "Hourly storage sync failed: %s; retrying in %s seconds",
                exc,
                retry_delay,
            )
            await asyncio.sleep(retry_delay)
            continue

        logger.info("Hourly storage sync completed successfully; next run in %s seconds", hourly_delay)
        await asyncio.sleep(hourly_delay)


@app.on_event("startup")
async def start_hourly_storage_sync():
    global HOURLY_SYNC_TASK
    HOURLY_SYNC_TASK = asyncio.create_task(_hourly_storage_sync_loop())


@app.on_event("shutdown")
async def stop_hourly_storage_sync():
    global HOURLY_SYNC_TASK
    if HOURLY_SYNC_TASK:
        HOURLY_SYNC_TASK.cancel()
        try:
            await HOURLY_SYNC_TASK
        except asyncio.CancelledError:
            pass
        HOURLY_SYNC_TASK = None



base_dir = os.path.dirname(os.path.abspath(__file__))
FAVICON_ICO_BASE64 = "AAABAAIAEBAAAAAAIAAMAwAAJgAAACAgAAAAACAALAEAADIDAACJUE5HDQoaCgAAAA1JSERSAAAAEAAAABAIBgAAAB/z/2EAAALTSURBVHicbZNNaJxVFIafc7+fySTDTNI0M7Y6gm3T1mDUJkVrEmwlaAqidNNVFYsgTXcRcRsXRVBwZTVWsSgI6sIoaKu0NijYLLJoC4oYAnbRpkI7Del0Zky+n3uPi5mSpni35z4v5/C+rxRKfco9T4yHOouzKSICgPEzqFrQ9d/NelIQMcSNW9g0Iczm8DPteGGWqHYTtQli1iP+GmtQZ0mSiE2P7KX/wOuUtg+AhThyzP/8MQszn7FaqxC05VDnmlyh1KeIgDpcmjI0fpwtQ2NcuzTH5QszRKnSU+xhx+irIAmzJyZYvPQjYUcX6mzzBBEhjVYYHv+ILUNj/PLeEU699Tz31ec4NKD8Mf020xOPUVm4yL6JKUo7h0mjRvOczk392p4v69ahl/XoadXt+45oLn+/4m/W9098oaqqm7c+qW35B7W7vFt7nz6sGx7YpYXiw1oo9alBHV4Q8uiBN1m8OMfVC6fIFoqAcvt2lSRJcTYm054njRv88/uvpNEK3HHH2YSwo5Oe3n6u/zWLOtsaKr7vEQQ+UZRQXV4mzGQIsrl1ThgQ1DlsrHhhG3e7Ur1VA2D6q08YfWaEypVr/58DVYdzFuMFiBhSayl0d/HB1EkOvnSUp/YMcu6nrzn2ziTGGJC7NvCCkNVqhfmzn9I7ephMrps0+hfxfMTz+P6Hs5S3PcGZc+ep3LhOdWkJl6wgLRHjnCPI5liYOYmIx/D4cVySENeXEZSO9gyNep39z47x7exVXjj2DRseepw0booYVDF+SFRb4vzUa5QH97J/8juKO0cQ8RA/S65zI3teeZcXJz9HrU/9xhWMH6BoK4mtAsWNZYo7Rth18A02btuNjV1zVQFnHfNnpvjz9Ic4m+CFWVC3JnBHJFmt4VJLeeA5enoHsUmzlX//9iWNm4tkcl2ICNpyQ+6tsxgPVIlXath4tRkJEcJsHhO2odYCa8h/udc+u/XASWAAAAAASUVORK5CYIKJUE5HDQoaCgAAAA1JSERSAAAAIAAAACAIBgAAAHN6evQAAADzSURBVHic7ZfBDYMwDEVN1XuPObFAD4hVWIVBWIVVUFfoqVO0p1RRGjv+TiASqk9I4P9fEmxMd3P3NzWMS0vzPwAR0dWSNC0be2+dR0irQ15CydgKogJAjFGQLEDKXBJFnxcBYjHkfLW5LIBG4PV8fK9dP5g0VGWIvtlx3jqPrEYSICS3mmvzmzeiH4CaqzcBHB3nAQhLsgmAh0BBigBcPyQbEAJRZQc4CA1IshWXlGKuPcdRvQq8qcacBQhXbZkFQvNcvmoHrAOJz5uWjdVgAeKzRyGK5wHJ+LCJSBLVRvFMaAWpOhUjILv+F+wR5/kcW+MDD4Ry63oCipIAAAAASUVORK5CYII="

static_dir = os.path.join(base_dir, "static")
templates_dir = os.path.join(base_dir, "templates")

os.makedirs(static_dir, exist_ok=True)
os.makedirs(templates_dir, exist_ok=True)

app.mount("/static", StaticFiles(directory=static_dir), name="static")
templates = Jinja2Templates(directory=templates_dir)


@app.get("/favicon.ico", include_in_schema=False)
async def favicon():
    return Response(
        content=base64.b64decode(FAVICON_ICO_BASE64),
        media_type="image/x-icon",
        headers={"Cache-Control": "public, max-age=86400"},
    )


def verify_session(request: Request) -> bool:
    return request.cookies.get("session_pin") == APP_PIN


def shift_date(date_str: str, days: int) -> str:
    """Shift a YYYY-MM-DD date by the requested number of days."""
    return (datetime.date.fromisoformat(date_str) + datetime.timedelta(days=days)).isoformat()


def get_recent_dates(n: int = 5) -> list[str]:
    """Return last n dates including today, most recent first."""
    today = datetime.date.today()
    return [(today - datetime.timedelta(days=i)).isoformat() for i in range(n)]


# ---------- Auth ----------

@app.get("/login", response_class=HTMLResponse)
async def login_get(request: Request):
    return templates.TemplateResponse(request, "login.html", {})


@app.post("/login")
async def login_post(request: Request, pin: str = Form(...)):
    if pin == APP_PIN:
        response = RedirectResponse(url="/", status_code=status.HTTP_302_FOUND)
        response.set_cookie(key="session_pin", value=pin, httponly=True)
        return response
    return templates.TemplateResponse(request, "login.html", {"error": "Неверный PIN"})


@app.get("/logout")
async def logout():
    response = RedirectResponse(url="/login")
    response.delete_cookie("session_pin")
    return response


# ---------- Storage API ----------

@app.api_route("/api/storage/sync", methods=["GET", "POST"])
async def storage_sync_api(
    request: Request,
    month: str | None = None,
    force: bool = False,
):
    if not verify_session(request):
        raise HTTPException(status_code=401, detail="Unauthorized")
    target_month = month or (datetime.date.today().replace(day=1) - datetime.timedelta(days=1)).strftime("%Y-%m")
    try:
        result = await asyncio.to_thread(storage.sync_month, target_month, force)
        return JSONResponse(result)
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e))
    except Exception as e:
        logger.exception("Manual storage sync failed for %s", target_month)
        raise HTTPException(status_code=502, detail=str(e))


# ---------- Main form ----------

@app.get("/", response_class=HTMLResponse)
async def index(request: Request, background_tasks: BackgroundTasks, date: str = None):
    if not verify_session(request):
        return RedirectResponse(url="/login")

    today = datetime.date.today().isoformat()
    if not date:
        date = today

    # The form must stay usable for manual entry even if Obsidian is down —
    # so a failed read here just means "no prefill", not an error page.
    try:
        content = get_note_cached(date)
    except ObsidianFetchError as e:
        logger.warning(f"Could not read note for {date} from active storage, showing blank form: {e}")
        content = None

    data = {
        "sleep_hours": "",
        "pulse_avg_day": "",
        "pulse_avg_sleep": "",
        "steps_total": "",
        "well_being": 9,
        "sleep_quality": 9,
        "alco": False,
        "notes": ""
    }

    if content:
        if content.startswith("---"):
            parts = content.split("---", 2)
            if len(parts) >= 3:
                frontmatter = yaml.safe_load(parts[1]) or {}
                for k in data.keys():
                    if k in frontmatter:
                        data[k] = frontmatter[k]
                data["notes"] = parts[2].replace("## Заметки\n\n", "").strip()

    recent_dates = get_recent_dates(5)

    def preload_cache():
        for d in recent_dates:
            try:
                get_note_cached(d)
            except ObsidianFetchError:
                pass  # best-effort warmup, ignore failures silently

    background_tasks.add_task(preload_cache)

    return templates.TemplateResponse(request, "form.html", {
        "date": date,
        "today": today,
        "data": data,
        "recent_dates": recent_dates,
    })


# ---------- Save (manual edit from the web form — snapshot-aware merge) ----------

@app.post("/save")
async def save(request: Request,
               date: str = Form(...),
               sleep_hours: float = Form(0),
               pulse_avg_day: int = Form(0),
               pulse_avg_sleep: int = Form(0),
               steps_total: int = Form(0),
               well_being: int = Form(9),
               sleep_quality: int = Form(9),
               alco: bool = Form(False),
               notes: str = Form(""),
               original_sleep_hours: str = Form(""),
               original_pulse_avg_day: str = Form(""),
               original_pulse_avg_sleep: str = Form(""),
               original_steps_total: str = Form(""),
               original_well_being: str = Form("9"),
               original_sleep_quality: str = Form("9"),
               original_alco: str = Form("false"),
               original_notes: str = Form(""),
               navigate: str = Form("save")):
    if not verify_session(request):
        raise HTTPException(status_code=401, detail="Unauthorized")
    if not 0 <= sleep_quality <= 9:
        raise HTTPException(status_code=422, detail="sleep_quality must be between 0 and 9")

    logger.info(f"/save called for {date}: action={navigate}, sleep={sleep_hours}h, pulse_day={pulse_avg_day}, "
                f"pulse_sleep={pulse_avg_sleep}, steps={steps_total}, well_being={well_being}, sleep_quality={sleep_quality}, alco={alco}")

    # Read the current note before saving. The hidden original_* values are the
    # snapshot shown when this form was opened. If a field has not changed in
    # the form, keep the CURRENT value from Obsidian instead of writing the
    # possibly stale value from the browser. This is especially important for
    # fields also updated by Android /sync.
    try:
        existing_content = storage.get_note_content(date)
        existing = parse_note(existing_content)
    except ObsidianFetchError as e:
        logger.error(f"❌ /save: cannot read current state for {date}, aborting: {e}")
        raise HTTPException(
            status_code=502,
            detail=f"Cannot verify current note state for {date}, aborting save to avoid data loss: {e}"
        )

    def parse_float_snapshot(value: str) -> float:
        try:
            return round(float(value), 1) if value.strip() else 0.0
        except ValueError:
            return 0.0

    def parse_int_snapshot(value: str) -> int:
        try:
            return int(value) if value.strip() else 0
        except ValueError:
            return 0

    def parse_bool_snapshot(value: str) -> bool:
        return value.strip().lower() in ("1", "true", "yes", "on")

    submitted_sleep_hours = round(sleep_hours, 1)
    submitted_pulse_avg_day = pulse_avg_day
    submitted_pulse_avg_sleep = pulse_avg_sleep
    submitted_steps_total = steps_total

    sleep_hours_changed = submitted_sleep_hours != parse_float_snapshot(original_sleep_hours)
    pulse_avg_day_changed = submitted_pulse_avg_day != parse_int_snapshot(original_pulse_avg_day)
    pulse_avg_sleep_changed = submitted_pulse_avg_sleep != parse_int_snapshot(original_pulse_avg_sleep)
    steps_total_changed = submitted_steps_total != parse_int_snapshot(original_steps_total)
    well_being_changed = well_being != parse_int_snapshot(original_well_being)
    sleep_quality_changed = sleep_quality != parse_int_snapshot(original_sleep_quality)
    alco_changed = alco != parse_bool_snapshot(original_alco)
    notes_changed = notes != original_notes

    final_sleep_hours = submitted_sleep_hours if sleep_hours_changed else existing["sleep_hours"]
    final_pulse_avg_day = submitted_pulse_avg_day if pulse_avg_day_changed else existing["pulse_avg_day"]
    final_pulse_avg_sleep = submitted_pulse_avg_sleep if pulse_avg_sleep_changed else existing["pulse_avg_sleep"]
    final_steps_total = submitted_steps_total if steps_total_changed else existing["steps_total"]
    final_well_being = well_being if well_being_changed else existing["well_being"]
    final_sleep_quality = sleep_quality if sleep_quality_changed else existing["sleep_quality"]
    final_alco = alco if alco_changed else existing["alco"]
    final_notes = notes if notes_changed else existing["notes"]

    frontmatter = {
        "project": "sleepmon",
        "created": date,
        "related": build_related_link(date),
        "sleep_hours": final_sleep_hours,
        "pulse_avg_day": final_pulse_avg_day,
        "pulse_avg_sleep": final_pulse_avg_sleep,
        "steps_total": final_steps_total,
        "sleep_awakenings": existing["sleep_awakenings"],
        "well_being": final_well_being,
        "sleep_quality": final_sleep_quality,
        "alco": final_alco
    }

    yaml_content = yaml.dump(frontmatter, sort_keys=False, allow_unicode=True)
    note_content = f"---\n{yaml_content}---\n\n## Заметки\n\n{final_notes}"

    success = storage.save_note_content(date, note_content)

    if success:
        set_note_cache(date, note_content)
        logger.info(
            f"✅ /save: note for {date} saved successfully "
            f"(changed: sleep={sleep_hours_changed}, pulse_day={pulse_avg_day_changed}, "
            f"pulse_sleep={pulse_avg_sleep_changed}, steps={steps_total_changed}, "
            f"well_being={well_being_changed}, sleep_quality={sleep_quality_changed}, "
            f"alco={alco_changed}, notes={notes_changed})"
        )
        target_date = date
        if navigate == "prev":
            target_date = shift_date(date, -1)
        elif navigate == "next":
            target_date = shift_date(date, 1)
        elif navigate == "today":
            target_date = datetime.date.today().isoformat()
        return RedirectResponse(url=f"/?date={target_date}", status_code=status.HTTP_302_FOUND)
    else:
        logger.error(f"❌ /save: failed to save note for {date} to active storage")
        return HTMLResponse(
            content=f"<h2>Ошибка сохранения</h2><p><a href='/?date={date}'>Назад</a></p>",
            status_code=500
        )


# ---------- Sync (Android Companion App — merge-write, never touches user fields) ----------

@app.post("/sync")
async def sync_endpoint(request: Request,
               date: str = Form(...),
               sleep_hours: float = Form(0),
               pulse_avg_day: int = Form(0),
               pulse_avg_sleep: int = Form(0),
               steps_total: int = Form(0),
               sleep_awakenings: int = Form(0)):
    """Automatic periodic sync from the Android app.

    Unlike /save (manual form save, full overwrite), this endpoint MERGES with
    the existing note:

    - `alco` and free-text `notes` are NEVER touched here (user-owned).
    - `well_being` and `sleep_quality` are preserved: both are subjective user-owned fields and are never changed by automatic sync.
    - Sleep fields (`sleep_hours`, `sleep_awakenings`, `pulse_avg_sleep`) are
      replaced as a group only when the incoming night is LONGER than the stored
      one (the band re-sends an in-progress night with growing duration). Equal
      or shorter nights never replace; with equal duration only empty fields are
      filled. `sleep_awakenings` is the count of distinct awakenings in the night.
    - `steps_total` is ALWAYS updated — it accumulates throughout the day and is the only stored step counter.
    - `pulse_avg_day` is updated when the incoming value is > 0 (0 = no waking
      samples yet, keep the stored one); `pulse_avg_sleep` follows the sleep
      rules above.
    - `related` is recomputed from the note's own date every time (cheap,
      deterministic, and self-healing if it was ever wrong).

    CRITICAL #1: the "existing" note state used for the merge is read
    DIRECTLY from Obsidian, bypassing NOTE_CACHE. This is a merge-write —
    if we merged against a stale cached copy, a value edited directly in
    Obsidian (or by a /save on a different worker process) could get
    silently overwritten with an old value (e.g. well_being reset to 0
    even though the user just set it). /sync runs infrequently (every
    ~15 min), so the extra REST round-trip here is cheap; correctness
    matters far more than shaving off that one request.

    CRITICAL #2: if the existing note can't be reliably read (Obsidian down,
    unexpected error), this endpoint ABORTS with 502 instead of silently
    treating it as "no note exists" — that would recreate the note from
    scratch and wipe out any real data that just happened to be
    unreadable at that moment.
    """
    if request.cookies.get("session_pin") != APP_PIN:
        raise HTTPException(status_code=401, detail="Unauthorized")

    logger.info(f"/sync called for {date}: sleep={sleep_hours}h, pulse_day={pulse_avg_day}, "
                f"pulse_sleep={pulse_avg_sleep}, steps={steps_total}, "
                f"awakenings={sleep_awakenings}")

    try:
        content = storage.get_note_content(date)
    except ObsidianFetchError as e:
        logger.error(f"❌ /sync: cannot read current state for {date}, aborting: {e}")
        raise HTTPException(
            status_code=502,
            detail=f"Cannot verify current note state for {date}, aborting sync to avoid data loss: {e}"
        )

    existing = parse_note(content)

    # Sleep fields (sleep_hours, sleep_awakenings, pulse_avg_sleep) describe one night.
    # While the night is in progress the band re-sends it with growing duration
    # (2.2 h at 01:52 ... 7.7 h at 07:58), so a LONGER night replaces a shorter one.
    # Equal or shorter never replaces: a complete night and manual corrections stay safe.
    incoming_sleep_hours = round(sleep_hours, 1)
    existing_sleep_hours = existing["sleep_hours"] or 0
    replace_sleep = incoming_sleep_hours > existing_sleep_hours
    same_night = incoming_sleep_hours > 0 and incoming_sleep_hours == existing_sleep_hours

    if replace_sleep:
        final_sleep_hours = incoming_sleep_hours
        final_sleep_awakenings = sleep_awakenings
        final_pulse_avg_sleep = pulse_avg_sleep if pulse_avg_sleep > 0 else existing["pulse_avg_sleep"]
    else:
        final_sleep_hours = existing["sleep_hours"]
        # Same duration: only fill what is still empty (never overwrite a recorded value).
        final_sleep_awakenings = (
            sleep_awakenings if same_night and not existing["sleep_awakenings"] else existing["sleep_awakenings"]
        )
        final_pulse_avg_sleep = (
            pulse_avg_sleep if same_night and not existing["pulse_avg_sleep"] else existing["pulse_avg_sleep"]
        )

    # steps: ALWAYS update (accumulate throughout the day)
    final_steps_total = steps_total

    # Waking pulse: 0 means "no waking-hours samples yet" (e.g. only night data so far),
    # so it must not erase a value that was already recorded.
    final_pulse_avg_day = pulse_avg_day if pulse_avg_day > 0 else existing["pulse_avg_day"]

    # well_being: preserve the existing value exactly. For a brand-new note,
    # parse_note() supplies the creation default of 9.
    well_being = existing["well_being"]

    frontmatter = {
        "project": "sleepmon",
        "created": date,
        "related": build_related_link(date),
        "sleep_hours": final_sleep_hours,
        "pulse_avg_day": final_pulse_avg_day,
        "pulse_avg_sleep": final_pulse_avg_sleep,
        "steps_total": final_steps_total,
        "sleep_awakenings": final_sleep_awakenings,
        "well_being": well_being,
        "sleep_quality": existing["sleep_quality"],
        "alco": existing["alco"]
    }

    yaml_content = yaml.dump(frontmatter, sort_keys=False, allow_unicode=True)
    note_content = f"---\n{yaml_content}---\n\n## Заметки\n\n{existing['notes']}"

    success = storage.save_note_content(date, note_content)

    if success:
        # Do not report success merely because the write request returned 2xx.
        # Read the note back from the source of truth (Obsidian) and verify that
        # the step counters actually persisted. This makes a deployment/API/cache
        # problem visible to Android instead of silently accepting a false success.
        try:
            saved_content = storage.get_note_content(date)
            saved = parse_note(saved_content)
        except ObsidianFetchError as e:
            logger.error(f"❌ /sync: write succeeded but verification read failed for {date}: {e}")
            raise HTTPException(status_code=502, detail=f"Sync write could not be verified for {date}: {e}")

        if saved["steps_total"] != final_steps_total:
            logger.error(
                f"❌ /sync: steps verification failed for {date}: "
                f"sent={final_steps_total}, saved={saved['steps_total']}"
            )
            raise HTTPException(
                status_code=502,
                detail=(
                    f"Steps were not persisted for {date}: "
                    f"sent={final_steps_total}, saved={saved['steps_total']}"
                ),
            )

        set_note_cache(date, saved_content or note_content)
        logger.info(
            f"✅ /sync: note for {date} saved and verified "
            f"(steps_total={final_steps_total}, sleep={final_sleep_hours}h"
            f"{' [sleep updated]' if replace_sleep else ' [sleep preserved]'})"
        )
        return JSONResponse({
            "status": "ok",
            "date": date,
            "steps_total": saved["steps_total"],
        })
    else:
        logger.error(f"❌ /sync: failed to save note for {date} to Obsidian")
        raise HTTPException(status_code=502, detail=f"Failed to save note in Obsidian for {date}")
