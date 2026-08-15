"""
obsidian.py — клиент к Obsidian Local REST API.

Все операции с vault (чтение и запись заметок) проходят только через этот модуль.
Прямая файловая запись в vault намеренно не используется, чтобы Obsidian
видел изменения через свой live-кеш метаданных.

TLS-проверка отключена (verify=False), так как Obsidian использует
самоподписанный сертификат и трафик не покидает Docker-сеть.
"""

import httpx
from app.config import OBSIDIAN_BASE_URL, OBSIDIAN_API_KEY


def _note_path(date_str: str) -> str:
    """Вернуть путь к заметке в vault по дате в формате YYYY-MM-DD."""
    year = date_str[:4]
    return f"55-sleepmon/{year}/{date_str}.md"


def get_note_content(date_str: str) -> str | None:
    """
    Прочитать содержимое заметки за указанную дату.

    Args:
        date_str: Дата в формате YYYY-MM-DD.

    Returns:
        Текст заметки в формате markdown, либо None если заметки нет
        или Obsidian недоступен.
    """
    path = _note_path(date_str)
    url = f"{OBSIDIAN_BASE_URL}/vault/{path}"
    headers = {"Authorization": f"Bearer {OBSIDIAN_API_KEY}"}

    try:
        with httpx.Client(verify=False) as client:
            response = client.get(url, headers=headers)
            if response.status_code == 200:
                return response.text
            return None
    except Exception as e:
        print(f"Error fetching note from obsidian: {e}")
        return None


def save_note_content(date_str: str, content: str) -> bool:
    """
    Создать или полностью перезаписать заметку за указанную дату.

    Использует PUT /vault/{path} — Obsidian Local REST API создаёт
    недостающие папки автоматически.

    Args:
        date_str: Дата в формате YYYY-MM-DD.
        content: Полное содержимое файла (frontmatter + тело).

    Returns:
        True при успешном сохранении, False при ошибке.
    """
    path = _note_path(date_str)
    url = f"{OBSIDIAN_BASE_URL}/vault/{path}"
    headers = {
        "Authorization": f"Bearer {OBSIDIAN_API_KEY}",
        "Content-Type": "text/markdown"
    }

    try:
        with httpx.Client(verify=False) as client:
            response = client.put(url, headers=headers, content=content.encode("utf-8"))
            return response.status_code in (200, 201, 204)
    except Exception as e:
        print(f"Error saving note to obsidian: {e}")
        return False
