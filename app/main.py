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


# ---------- Save (Receives data from Android Companion) ----------

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
    # No verify_session for POST from Android App, or check a token/cookie
    if request.cookies.get("session_pin") != APP_PIN:
        raise HTTPException(status_code=401, detail="Unauthorized")

    steps_total = steps_1 + steps_2

    frontmatter = {
        "project": "sleepmon",
        "created": date,
        "related": "[[55-sleepmon/index]]",
        "sleep_hours": sleep_hours,
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
