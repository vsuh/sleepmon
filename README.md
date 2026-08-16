# Sleep Monitor

Личный веб-сервис для ведения дневника сна, пульса, шагов и самочувствия — данные попадают прямо в заметки Obsidian. Однопользовательское, PIN-защищённое приложение без встроенной аналитики (графики и статистику считает сам Obsidian через Dataview).

Данные с Xiaomi Mi Band приходят автоматически через **Android Companion App** (Health Connect → сервер), либо вводятся вручную через веб-форму.

---

## Архитектура

```
┌─────────────────┐        Health Connect       ┌──────────────────────┐
│   Xiaomi Mi Band │ ───────────────────────────▶│  Android Companion   │
│   (via Mi Fitness)│                             │  App (шаги/пульс/сон)│
└─────────────────┘                              └──────────┬───────────┘
                                                              │ POST /sync
                                                              │ (раз в час, фон)
                                                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│                         Docker Compose (сервер HRLOR)                 │
│                                                                        │
│  ┌────────────────────┐   Local REST API    ┌────────────────────┐   │
│  │  obsidian           │◀────HTTPS:27124────▶│  app (FastAPI)     │   │
│  │  sytone/obsidian-   │                     │  :8000             │   │
│  │  remote (headless,  │                     └────────────────────┘   │
│  │  Xvfb + noVNC :8080)│                                              │
│  └──────────┬───────────┘                                              │
│             │ /vault (rw)                                             │
└─────────────┼──────────────────────────────────────────────────────────┘
              │
              ▼
     Syncthing-синхронизируемый vault
     (Windows ↔ HRLOR ↔ прочие устройства)
```

- **`obsidian`** — headless Obsidian (Electron внутри Xvfb) с плагином **Local REST API**. Веб-приложение никогда не пишет в файлы vault напрямую — только через REST API, чтобы не рассинхронизировать live-кеш метаданных Obsidian.
- **`app`** — FastAPI-бэкенд + отдаваемый им фронтенд (server-rendered формы).
- **Android Companion App** — читает Health Connect (Steps/HeartRate/Sleep), шлёт данные на сервер по HTTP.

---

## Структура заметок

```
55-sleepmon/<YYYY>/<YYYY-MM-DD>.md
```

Frontmatter:

```yaml
---
project: "sleepmon"
created: "2026-08-16"
related: "[[55-sleepmon/index]]"
sleep_hours: 7.5
pulse_avg_day: 68
pulse_avg_sleep: 58
steps_1: 7300
steps_2: 0
steps_total: 7300
well_being: 7
alco: false
---

## Заметки

(свободный текст)
```

---

## Два способа записи — `/save` и `/sync`

Это принципиальный момент архитектуры, важно понимать разницу:

| | `/save` | `/sync` |
|---|---|---|
| Кто вызывает | Веб-форма (кнопка "Сохранить") | Android Companion App |
| Поведение | **Полная перезапись** заметки | **Merge** с текущим содержимым |
| `sleep_hours`, `steps_1/2` | Всегда перезаписываются | **Fill-once**: пишутся только если в карточке сейчас `0`, и только ненулевым значением. Дальше трогает их только ручное сохранение |
| `pulse_avg_day/sleep` | Всегда перезаписываются | Всегда перезаписываются (естественно колеблющиеся показания) |
| `well_being` | Значение из формы | Сохраняется как есть, если не `0` |
| `alco`, `Заметки` | Значение из формы | **Никогда не трогаются** |

Смысл: телефон может присылать данные каждый час, не боясь затереть то, что вы вручную поправили или заполнили в веб-форме.

---

## Требования

- Docker и Docker Compose
- Сервер на **x86_64** (headless-образ Obsidian не имеет ARM64-сборки — ARM-серверы/TV-приставки не подойдут)
- Настроенный Syncthing для синхронизации Obsidian-vault (вне зоны ответственности этого приложения)

---

## Установка и запуск

### 1. Конфигурация

Скопируйте `.env.example` в `.env`:

```env
APP_PIN=1234
OBSIDIAN_BASE_URL=https://obsidian:27124
OBSIDIAN_API_KEY=<получите после настройки плагина, см. ниже>
```

### 2. docker-compose.yml — ключевые моменты

```yaml
services:
  obsidian:
    image: sytone/obsidian-remote:latest
    volumes:
      - /path/to/syncthing/vault:/vault:rw
      - ./config/obsidian:/config:rw
    ports:
      - "8080:8080"    # VNC (первичная настройка)
      - "27124:27124"  # Local REST API

  app:
    build:
      context: .
      dockerfile: app/Dockerfile   # Dockerfile лежит в app/, а не в корне!
    ports:
      - "8000:8000"
    env_file:
      - .env
    depends_on:
      - obsidian
```

> ⚠️ Build context для `app` должен быть корнем проекта (`.`), а не `./app` — иначе внутри контейнера пропадёт папка `app/` и `uvicorn app.main:app` упадёт с `ModuleNotFoundError: No module named 'app'`.

### 3. Запуск

```bash
docker compose up -d --build
```

---

## Первичная настройка Obsidian (через VNC)

Headless-режим требует ручной настройки один раз:

1. Откройте `http://<IP_СЕРВЕРА>:8080` в браузере
2. Откройте примонтированный vault (папка `/vault`)
3. Settings → Community plugins → отключите Safe Mode, установите и включите **Local REST API** (`coddingtonbear/obsidian-local-rest-api`)
4. В настройках плагина:
   - Скопируйте **API Key** → вставьте в `.env` как `OBSIDIAN_API_KEY`
   - **Обязательно** смените **Bind Address** с `127.0.0.1` на **`0.0.0.0`** — иначе контейнер `app` не сможет достучаться до Obsidian по внутренней docker-сети (плагин по умолчанию слушает только loopback самого контейнера `obsidian`, а не все интерфейсы)
5. Перезапустите:
   ```bash
   docker compose restart obsidian app
   ```

Проверка, что порт слушается правильно:
```bash
docker compose exec obsidian ss -tlnp | grep 27124
# Ожидаем: LISTEN  0.0.0.0:27124   (а не 127.0.0.1:27124)
```

---

## Android Companion App

Приложение читает Health Connect и шлёт данные на сервер. Исходники — `android_sync_app/`.

### Возможности

- **Sync Now** — синхронизация с начала текущего месяца по сегодня
- **Sync Range** — выбор произвольного диапазона дат ("От"/"До") для массового бэкфилла
- **Фоновая синхронизация** — автоматически каждый **1 час** через WorkManager, работает даже после закрытия приложения; переживает перезагрузку телефона (`BootReceiver`)
- **Резервный сервер** — если основной адрес не отвечает **5 секунд**, приложение переключается на резервный (полезно для доступа вне домашней сети через reverse-proxy)

### Сборка

```bash
cd android_sync_app
./gradlew assembleDebug        # Linux/Mac/WSL
gradlew.bat assembleDebug      # Windows cmd
```

APK появится в `app/build/outputs/apk/debug/app-debug.apk`. Установка через USB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Либо соберите и запустите прямо из Android Studio.

**Пересобирайте APK после каждого изменения в `android_sync_app/`** — иначе на телефоне останется старая логика.

### Настройка приложения

При первом запуске укажите в полях:
- **Server URL (основной)** — например, `http://192.168.X.X:8000` (локальная сеть)
- **Server URL (резервный)** — внешний адрес через reverse-proxy, например `https://sm.vsuh.duckdns.org:9124`
- **App PIN** — тот же, что в `.env` сервера

При первом запуске нужно выдать разрешения Health Connect на **Steps, Heart Rate, Sleep** — без них синхронизация не сработает.

---

## Внешний доступ (nginx reverse-proxy на сервере)

Чтобы резервный адрес из Android-приложения работал вне дома, проксируйте внешний HTTPS-порт на внутренний `app:8000`:

```nginx
server {
    listen 9124 ssl;
    listen [::]:9124 ssl;
    server_name sm.vsuh.duckdns.org;

    ssl_certificate     /etc/letsencrypt/live/sm.vsuh.duckdns.org/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/sm.vsuh.duckdns.org/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;

    location / {
        proxy_pass         http://127.0.0.1:8000;
        proxy_set_header   Host $host;
        proxy_set_header   X-Real-IP $remote_addr;
        proxy_set_header   X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_pass_header  Set-Cookie;
    }
}
```

Не забудьте пробросить порт 9124 на роутере и настроить автопродление сертификата (`certbot renew`).

---

## Использование

Откройте `http://<IP_СЕРВЕРА>:8000`, войдите по `APP_PIN`. Главный экран — сегодняшняя запись, можно листать последние 5 дней или выбрать дату вручную. Данные с телефона появляются автоматически; здесь их можно проверить и поправить.

---

## Известные проблемы и troubleshooting

### `ModuleNotFoundError: No module named 'app'`
Неверный build context в `docker-compose.yml`. Должно быть `context: .` + `dockerfile: app/Dockerfile` (см. раздел установки выше).

### `Connection refused` между `app` и `obsidian`
Плагин Local REST API слушает `127.0.0.1` вместо `0.0.0.0`. Смените **Bind Address** в настройках плагина (через VNC) — см. раздел "Первичная настройка Obsidian".

### Контейнер `obsidian` "теряет" vault после перезапуска
После рестарта/пересоздания контейнера иногда сбрасывается открытый в интерфейсе vault (даже если volume примонтирован верно) — Obsidian просто не помнит, какую папку открывать. Зайдите через VNC (`:8080`) и заново откройте `/vault` вручную. Также после такого пересоздания стоит **перепроверить Bind Address плагина** — настройки могли не сохраниться, если `/config` не был примонтирован как persistent volume.

### `404 Not Found` на `/sync` или `/backfill`
Контейнер `app` не пересобран после изменения кода — Docker `COPY . .` копирует файлы только при `docker compose build`, не отслеживает изменения "на лету":
```bash
docker compose up --build app
```

### Пульс/сон не приходят с браслета, хотя Mi Fitness вроде синхронизирован
Это проблема на стороне **Mi Fitness ↔ Health Connect**, не в коде приложения. Health Connect — пассивное хранилище, оно не умеет "запросить данные заново" — переслать их должен сам Mi Fitness. Что помогает (по убыванию эффективности):
1. Настройки телефона → Health Connect → Приложения и разрешения → Mi Fitness → снять все разрешения → выдать заново
2. Очистить кэш (не данные!) приложения Mi Fitness
3. Отключить/включить синхронизацию с Health Connect в самом Mi Fitness, затем нажать "Sync Now" в нашем приложении
4. Force stop Mi Fitness + повторная Bluetooth-синхронизация с браслетом

Проверяйте именно в **Health Connect** ("Здоровье и спорт" → Просмотр данных), а не в Google Fit — это разные хранилища, Google Fit больше не используется этим проектом.

---

## Технологический стек

- **Backend**: Python / FastAPI, Jinja2, взаимодействие с vault только через Obsidian Local REST API
- **Frontend**: server-rendered HTML-формы, адаптивная вёрстка
- **Android**: Kotlin, Jetpack Compose, Health Connect API, WorkManager (фоновая синхронизация), OkHttp
- **Инфраструктура**: Docker Compose, Syncthing, nginx (внешний доступ)
