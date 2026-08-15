"""
config.py — центральная конфигурация приложения Sleep Monitor.

Все значения загружаются из переменных окружения (файл .env в корне проекта).
Секреты (пин, токены, ключи API) никогда не хранятся в коде — только здесь.
"""

import os
from dotenv import load_dotenv

load_dotenv()

# --- Приложение ---

APP_PIN: str = os.getenv("APP_PIN", "0000")
"""PIN-код для входа в веб-интерфейс."""

# --- Obsidian Local REST API ---

OBSIDIAN_BASE_URL: str = os.getenv("OBSIDIAN_BASE_URL", "https://obsidian:27124")
"""Базовый URL Obsidian Local REST API (внутренний Docker-хост)."""

OBSIDIAN_API_KEY: str = os.getenv("OBSIDIAN_API_KEY", "")
"""API-ключ плагина Obsidian Local REST API."""

