# Xiaomi Cloud sleep probe

Отдельный диагностический скрипт для проверки, есть ли данные сна в Xiaomi Mi Fitness Cloud.

## Запуск в Windows

Из каталога `tools/xiaomi_cloud_probe`:

```bat
py -3.11 -m venv .venv
.venv\Scripts\activate
python -m pip install -r requirements.txt
copy .env.example .env
```

Заполните в `.env`:

- `XIAOMI_USER_ID`
- `XIAOMI_PASS_TOKEN`
- `XIAOMI_REGION` (по умолчанию `ru`)

Запуск:

```bat
python probe.py
```

По умолчанию проверяется диапазон 2026-10-06 .. 2026-10-07. Можно задать другой диапазон:

```bat
python probe.py --start 2026-10-06 --end 2026-10-08
```

Результат сохраняется в локальный `sleep_cloud.json`. Секреты в консоль не выводятся и в Git не добавляются.
