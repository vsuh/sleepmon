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


class ObsidianFetchError(Exception):
    """
    Заметка не может быть достоверно прочитана: Obsidian недоступен,
    вернул неожиданный статус, или сеть отвалилась.

    ВАЖНО: это НЕ означает "заметки не существует" — это означает
    "мы не знаем, есть ли там данные, и не должны их затирать".
    Отличается от обычного None (= заметки точно нет, 404), который
    безопасно трактовать как "новая заметка".
    """
    pass


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
        Текст заметки в формате markdown, либо None если заметки
        действительно нет (404 от Obsidian).

    Raises:
        ObsidianFetchError: если Obsidian недоступен или вернул
            неожиданный статус — то есть мы НЕ можем достоверно сказать,
            есть заметка или нет. Вызывающий код не должен в этом случае
            молча трактовать это как "новая заметка", чтобы не затереть
            реальные данные, если она на самом деле существует.
    """
    path = _note_path(date_str)
    url = f"{OBSIDIAN_BASE_URL}/vault/{path}"
    headers = {"Authorization": f"Bearer {OBSIDIAN_API_KEY}"}

    try:
        with httpx.Client(verify=False) as client:
            response = client.get(url, headers=headers)
    except Exception as e:
        print(f"Error fetching note from obsidian: {e}")
        raise ObsidianFetchError(str(e)) from e

    if response.status_code == 200:
        return response.text
    if response.status_code == 404:
        return None

    # Любой другой статус (5xx, 401, таймаут плагина и т.п.) — тоже
    # трактуем как "не смогли прочитать", а НЕ как "заметки нет".
    raise ObsidianFetchError(f"Unexpected status {response.status_code} for {path}")


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
