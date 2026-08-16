"""
config.py вЂ” С†РµРЅС‚СЂР°Р»СЊРЅР°СЏ РєРѕРЅС„РёРіСѓСЂР°С†РёСЏ РїСЂРёР»РѕР¶РµРЅРёСЏ Sleep Monitor.

Р’СЃРµ Р·РЅР°С‡РµРЅРёСЏ Р·Р°РіСЂСѓР¶Р°СЋС‚СЃСЏ РёР· РїРµСЂРµРјРµРЅРЅС‹С… РѕРєСЂСѓР¶РµРЅРёСЏ (С„Р°Р№Р» .env РІ РєРѕСЂРЅРµ РїСЂРѕРµРєС‚Р°).
РЎРµРєСЂРµС‚С‹ (РїРёРЅ, С‚РѕРєРµРЅС‹, РєР»СЋС‡Рё API) РЅРёРєРѕРіРґР° РЅРµ С…СЂР°РЅСЏС‚СЃСЏ РІ РєРѕРґРµ вЂ” С‚РѕР»СЊРєРѕ Р·РґРµСЃСЊ.
"""

import os
from dotenv import load_dotenv

load_dotenv()

# --- РџСЂРёР»РѕР¶РµРЅРёРµ ---

APP_PIN: str = os.getenv("APP_PIN", "0000")
"""PIN-РєРѕРґ РґР»СЏ РІС…РѕРґР° РІ РІРµР±-РёРЅС‚РµСЂС„РµР№СЃ."""

# --- Obsidian Local REST API ---

OBSIDIAN_BASE_URL: str = os.getenv("OBSIDIAN_BASE_URL", "https://obsidian:27124")
"""Р‘Р°Р·РѕРІС‹Р№ URL Obsidian Local REST API (РІРЅСѓС‚СЂРµРЅРЅРёР№ Docker-С…РѕСЃС‚)."""

OBSIDIAN_API_KEY: str = os.getenv("OBSIDIAN_API_KEY", "")
"""API-РєР»СЋС‡ РїР»Р°РіРёРЅР° Obsidian Local REST API."""

