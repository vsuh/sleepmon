from fastapi import FastAPI, Request, Form, HTTPException, status, BackgroundTasks
from fastapi.responses import HTMLResponse, RedirectResponse, JSONResponse
from fastapi.templating import Jinja2Templates
from fastapi.staticfiles import StaticFiles
import datetime
import yaml
import os
import asyncio
import logging

from app.config import APP_PIN
from app import obsidian

NOTE_CACHE: dict[str, str] = {}

def get_note_cached(date_str: str) -> str | None:
    if date_str in NOTE_CACHE:
        return NOTE_CACHE[date_str]
    content = obsidian.get_note_content(date_str)
    if content:
        NOTE_CACHE[date_str] = content
    return content

def set_note_cache(date_str: str, content: str):
    NOTE_CACHE[date_str] = content


def parse_note(content: str | None) -> dict:
    """Extract the fields we need to preserve/merge on a /sync write.

    Defaults assume a brand-new note (nothing recorded yet):
    well_being unset, no alcohol flag, no free-text notes, and the
    "fill-once" numeric fields (sleep_hours/steps_1/steps_2) at 0.
    """
    result = {
        "well_being": 0,
        "alco": False,
        "notes": "",
        "sleep_hours": 0,
        "steps_1": 0,
        "steps_2": 0,
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
    return result


logger = logging.getLogger("sleepmon")


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

    content = get_note_cached(date)

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
            get_note_cached(d)
            
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

    steps_total = steps_1 + steps_2

    frontmatter = {
        "project": "sleepmon",
        "created": date,
        "related": "[[55-sleepmon/index]]",
        "sleep_hours": round(sleep_hours, 1),
        "pulse_avg_day": pulse_avg_day,
        "pulse_avg_sleep": pulse_avg_sleep,
        "steps_1": steps_1,
        "steps_2": steps_2,
        "steps_total": steps_total,
        "well_being": well_being,
        "alco": alco
    }

    yaml_content = yaml.dump(frontmatter, sort_keys=False, allow_unicode=True)
    note_content = f"---\n{yaml_content}---\n\n## Заметки\n\n{notes}"

    success = obsidian.save_note_content(date, note_content)

    if success:
        set_note_cache(date, note_content)
        return RedirectResponse(url=f"/?date={date}", status_code=status.HTTP_302_FOUND)
    else:
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
               steps_2: int = Form(0)):
    """Automatic hourly sync from the Android app.

    Unlike /save (manual form save, full overwrite), this endpoint MERGES with
    the existing note:

    - `alco` and free-text `notes` are NEVER touched here (user-owned).
    - `well_being` is preserved unless it's still unset (0).
    - `sleep_hours`, `steps_1`, `steps_2` (and the derived `steps_total`) are
      "fill-once" fields: they're only written when the EXISTING value in the
      note is 0, and only with a non-zero incoming value. Once a real value
      is recorded, sync will never overwrite it again — only a manual edit
      via the web form (/save) can change it after that.
    - `pulse_avg_day` / `pulse_avg_sleep` are NOT fill-once: they reflect
      naturally fluctuating readings and are always updated on every sync.
    """
    if request.cookies.get("session_pin") != APP_PIN:
        raise HTTPException(status_code=401, detail="Unauthorized")

    existing = parse_note(get_note_cached(date))

    final_sleep_hours = existing["sleep_hours"] if existing["sleep_hours"] else round(sleep_hours, 1)
    final_steps_1 = existing["steps_1"] if existing["steps_1"] else steps_1
    final_steps_2 = existing["steps_2"] if existing["steps_2"] else steps_2
    steps_total = final_steps_1 + final_steps_2

    well_being = existing["well_being"] if existing["well_being"] else 0

    frontmatter = {
        "project": "sleepmon",
        "created": date,
        "related": "[[55-sleepmon/index]]",
        "sleep_hours": final_sleep_hours,
        "pulse_avg_day": pulse_avg_day,
        "pulse_avg_sleep": pulse_avg_sleep,
        "steps_1": final_steps_1,
        "steps_2": final_steps_2,
        "steps_total": steps_total,
        "well_being": well_being,
        "alco": existing["alco"]
    }

    yaml_content = yaml.dump(frontmatter, sort_keys=False, allow_unicode=True)
    note_content = f"---\n{yaml_content}---\n\n## Заметки\n\n{existing['notes']}"

    success = obsidian.save_note_content(date, note_content)

    if success:
        set_note_cache(date, note_content)
        return JSONResponse({"status": "ok", "date": date})
    else:
        raise HTTPException(status_code=502, detail=f"Failed to save note in Obsidian for {date}")
