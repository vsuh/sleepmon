from fastapi import FastAPI, Request, Form, HTTPException, status, BackgroundTasks
from fastapi.responses import HTMLResponse, RedirectResponse, JSONResponse
from fastapi.templating import Jinja2Templates
from fastapi.staticfiles import StaticFiles
import datetime
import yaml
import os
import asyncio
import logging
import time

from app.config import APP_PIN
from app import obsidian
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
logger = logging.getLogger("sleepmon")

NOTE_CACHE: dict[str, str] = {}

def get_note_cached(date_str: str) -> str | None:
    """Cached read. Used ONLY for UI display (index form), where a slightly
    stale value is harmless and speed matters. Propagates ObsidianFetchError
    to the caller (does NOT swallow it).

    NEVER use this for a merge-write (see /sync) — the cache has no
    invalidation for edits made directly in Obsidian or by other workers,
    so a merge based on cached "existing" data can silently overwrite a
    real value (e.g. well_being) with a stale one."""
    if date_str in NOTE_CACHE:
        return NOTE_CACHE[date_str]
    content = obsidian.get_note_content(date_str)
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
    well_being unset, no alcohol flag, no free-text notes, and the
    "fill-once" numeric fields (sleep_hours/steps_1/steps_2/sleep phases) at 0.
    """
    result = {
        "well_being": 0,
        "alco": False,
        "notes": "",
        "sleep_hours": 0,
        "steps_1": 0,
        "steps_2": 0,
        "sleep_light_min": 0,
        "sleep_deep_min": 0,
        "sleep_rem_min": 0,
        "sleep_awake_min": 0,
    }
    if content and content.startswith("---"):
        parts = content.split("---", 2)
        if len(parts) >= 3:
            frontmatter = yaml.safe_load(parts[1]) or {}
            result["well_being"] = frontmatter.get("well_being", 0)
            result["alco"] = frontmatter.get("alco", False)
            result["notes"] = parts[2].replace("## Заметки\n\n", "").strip()
            result["sleep_hours"] = frontmatter.get("sleep_hours", 0)
            result["steps_1"] = frontmatter.get("steps_1", 0)
            result["steps_2"] = frontmatter.get("steps_2", 0)
            result["sleep_light_min"] = frontmatter.get("sleep_light_min", 0)
            result["sleep_deep_min"] = frontmatter.get("sleep_deep_min", 0)
            result["sleep_rem_min"] = frontmatter.get("sleep_rem_min", 0)
            result["sleep_awake_min"] = frontmatter.get("sleep_awake_min", 0)
    return result


app = FastAPI()

base_dir = os.path.dirname(os.path.abspath(__file__))
static_dir = os.path.join(base_dir, "static")
templates_dir = os.path.join(base_dir, "templates")

os.makedirs(static_dir, exist_ok=True)
os.makedirs(templates_dir, exist_ok=True)

app.mount("/static", StaticFiles(directory=static_dir), name="static")
templates = Jinja2Templates(directory=templates_dir)


def verify_session(request: Request) -> bool:
    return request.cookies.get("session_pin") == APP_PIN


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
        logger.warning(f"Could not read note for {date}, showing blank form: {e}")
        content = None

    data = {
        "sleep_hours": "",
        "pulse_avg_day": "",
        "pulse_avg_sleep": "",
        "steps_1": "",
        "steps_2": "",
        "well_being": 5,
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


# ---------- Save (manual edit from the web form — full overwrite) ----------

@app.post("/save")
async def save(request: Request,
               date: str = Form(...),
               sleep_hours: float = Form(0),
               pulse_avg_day: int = Form(0),
               pulse_avg_sleep: int = Form(0),
               steps_1: int = Form(0),
               steps_2: int = Form(0),
               well_being: int = Form(5),
               alco: bool = Form(False),
               notes: str = Form("")):
    if not verify_session(request):
        raise HTTPException(status_code=401, detail="Unauthorized")

    logger.info(f"/save called for {date}: sleep={sleep_hours}h, pulse_day={pulse_avg_day}, "
                f"pulse_sleep={pulse_avg_sleep}, steps={steps_1}+{steps_2}, well_being={well_being}, alco={alco}")

    # Sleep-phase fields (sleep_light_min/deep/rem/awake) aren't part of this
    # form — they're populated by /sync from Health Connect. Read the current
    # note first so a manual save doesn't silently wipe them. Best-effort:
    # if Obsidian is unreachable, fall back to 0 rather than blocking the
    # manual save (form must stay usable even when Obsidian is down).
    try:
        existing_content = obsidian.get_note_content(date)
        existing = parse_note(existing_content)
    except ObsidianFetchError as e:
        logger.warning(f"/save: could not read existing note for {date} to preserve sleep phases: {e}")
        existing = parse_note(None)

    steps_total = steps_1 + steps_2

    frontmatter = {
        "project": "sleepmon",
        "created": date,
        "related": build_related_link(date),
        "sleep_hours": round(sleep_hours, 1),
        "pulse_avg_day": pulse_avg_day,
        "pulse_avg_sleep": pulse_avg_sleep,
        "steps_1": steps_1,
        "steps_2": steps_2,
        "steps_total": steps_total,
        "sleep_light_min": existing["sleep_light_min"],
        "sleep_deep_min": existing["sleep_deep_min"],
        "sleep_rem_min": existing["sleep_rem_min"],
        "sleep_awake_min": existing["sleep_awake_min"],
        "well_being": well_being,
        "alco": alco
    }

    yaml_content = yaml.dump(frontmatter, sort_keys=False, allow_unicode=True)
    note_content = f"---\n{yaml_content}---\n\n## Заметки\n\n{notes}"

    success = obsidian.save_note_content(date, note_content)

    if success:
        set_note_cache(date, note_content)
        logger.info(f"✅ /save: note for {date} saved successfully")
        return RedirectResponse(url=f"/?date={date}", status_code=status.HTTP_302_FOUND)
    else:
        logger.error(f"❌ /save: failed to save note for {date} to Obsidian")
        return HTMLResponse(
            content=f"<h2>Ошибка сохранения в Obsidian</h2><p><a href='/?date={date}'>Назад</a></p>",
            status_code=500
        )


# ---------- Sync (Android Companion App — merge-write, never touches user fields) ----------

@app.post("/sync")
async def sync_endpoint(request: Request,
               date: str = Form(...),
               sleep_hours: float = Form(0),
               pulse_avg_day: int = Form(0),
               pulse_avg_sleep: int = Form(0),
               steps_1: int = Form(0),
               steps_2: int = Form(0),
               sleep_light_min: int = Form(0),
               sleep_deep_min: int = Form(0),
               sleep_rem_min: int = Form(0),
               sleep_awake_min: int = Form(0)):
    """Automatic periodic sync from the Android app.

    Unlike /save (manual form save, full overwrite), this endpoint MERGES with
    the existing note:

    - `alco` and free-text `notes` are NEVER touched here (user-owned).
    - `well_being` is preserved unless it's still unset (0).
    - `sleep_hours` is "fill-once": only written when the existing value is 0,
      and only with a non-zero incoming value. Once a real value is recorded,
      sync will never overwrite it again (can't "re-measure" sleep mid-day).
      The sleep-phase breakdown (`sleep_light_min`/`sleep_deep_min`/
      `sleep_rem_min`/`sleep_awake_min`) shares this fill-once gate — they're
      derived from the same Health Connect sleep session as `sleep_hours`,
      so they fill together and freeze together.
    - `steps_1`, `steps_2` (and derived `steps_total`) are ALWAYS updated —
      they accumulate throughout the day and should reflect current totals.
    - `pulse_avg_day` / `pulse_avg_sleep` are NOT fill-once: they reflect
      naturally fluctuating readings and are always updated on every sync.
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
                f"pulse_sleep={pulse_avg_sleep}, steps={steps_1}, "
                f"phases(L/D/R/A)={sleep_light_min}/{sleep_deep_min}/{sleep_rem_min}/{sleep_awake_min}min")

    try:
        content = obsidian.get_note_content(date)
    except ObsidianFetchError as e:
        logger.error(f"❌ /sync: cannot read current state for {date}, aborting: {e}")
        raise HTTPException(
            status_code=502,
            detail=f"Cannot verify current note state for {date}, aborting sync to avoid data loss: {e}"
        )

    existing = parse_note(content)

    # sleep_hours (and phase breakdown) share one fill-once gate:
    # can't "remeasure" a night's sleep mid-day.
    should_fill_sleep = not existing["sleep_hours"]
    final_sleep_hours = round(sleep_hours, 1) if should_fill_sleep else existing["sleep_hours"]
    final_sleep_light_min = sleep_light_min if should_fill_sleep else existing["sleep_light_min"]
    final_sleep_deep_min = sleep_deep_min if should_fill_sleep else existing["sleep_deep_min"]
    final_sleep_rem_min = sleep_rem_min if should_fill_sleep else existing["sleep_rem_min"]
    final_sleep_awake_min = sleep_awake_min if should_fill_sleep else existing["sleep_awake_min"]

    # steps: ALWAYS update (accumulate throughout the day)
    final_steps_1 = steps_1
    final_steps_2 = steps_2
    steps_total = final_steps_1 + final_steps_2

    # well_being: preserve whatever is currently in Obsidian (freshly read above)
    well_being = existing["well_being"] if existing["well_being"] else 0

    frontmatter = {
        "project": "sleepmon",
        "created": date,
        "related": build_related_link(date),
        "sleep_hours": final_sleep_hours,
        "pulse_avg_day": pulse_avg_day,
        "pulse_avg_sleep": pulse_avg_sleep,
        "steps_1": final_steps_1,
        "steps_2": final_steps_2,
        "steps_total": steps_total,
        "sleep_light_min": final_sleep_light_min,
        "sleep_deep_min": final_sleep_deep_min,
        "sleep_rem_min": final_sleep_rem_min,
        "sleep_awake_min": final_sleep_awake_min,
        "well_being": well_being,
        "alco": existing["alco"]
    }

    yaml_content = yaml.dump(frontmatter, sort_keys=False, allow_unicode=True)
    note_content = f"---\n{yaml_content}---\n\n## Заметки\n\n{existing['notes']}"

    success = obsidian.save_note_content(date, note_content)

    if success:
        set_note_cache(date, note_content)
        logger.info(f"✅ /sync: note for {date} saved (steps_total={steps_total}, "
                    f"sleep={final_sleep_hours}h{' [filled]' if should_fill_sleep else ' [preserved]'})")
        return JSONResponse({"status": "ok", "date": date})
    else:
        logger.error(f"❌ /sync: failed to save note for {date} to Obsidian")
        raise HTTPException(status_code=502, detail=f"Failed to save note in Obsidian for {date}")
