---
project: Монитор здоровья с браслета
created: "2026-09-26"
related: "[[10-projects/index|index]]"
title: Sleep Monitor
---

# Sleep Monitor

Личная система дневника сна, пульса, активности и самочувствия.

Xiaomi Smart Band → Android Companion App → FastAPI → Obsidian Local REST API.

Проект однопользовательский. Аналитика выполняется в Obsidian/Dataview.

**Текущий Android build tag: v78 (02.10.2026).**

> v78 ещё не является финальным production APK: требуется E2E именно этого build и удаление диагностического dump сырых Xiaomi-файлов перед production.

## Архитектура

```
Xiaomi Smart Band
      │ secure Bluetooth Classic / RFCOMM (SPP)
      ▼
Android Companion App
      │ persistent queue
      │ POST /login → session cookie
      │ POST /sync
      ▼
FastAPI
      │ Obsidian Local REST API
      ▼
Obsidian vault
```

Ключевые правила:

- используется secure RFCOMM/SPP;
- после получения activity-файлов download-сеанс закрывается;
- после успешного /sync открывается отдельный SPP-сеанс для ACK;
- при ошибке сервера или ACK данные остаются в persistent queue;
- steps_total берётся из Xiaomi daily summary v5, когда summary доступен;
- автоматический /sync не изменяет well_being, sleep_quality, alco и свободный текст;
- запись в vault выполняется только через Obsidian Local REST API.

## Репозиторий

```
sleepmon/
├── app/                  # FastAPI
├── android_sync_app/     # Android Companion App
├── docker-compose.yml
├── .env.example
├── task.md               # актуальное состояние и следующий шаг
└── README.md
```

Разработка ведётся в checkout проекта внутри Obsidian vault. Production checkout находится отдельно на сервере HELOR.

## Android Companion App

Исходники находятся в `android_sync_app/`.

### Сборка

Windows:

```bat
cd android_sync_app
gradlew.bat :app:assembleDebug
```

Linux/macOS/WSL:

```bash
cd android_sync_app
./gradlew :app:assembleDebug
```

APK:

```
android_sync_app/app/build/outputs/apk/debug/app-debug.apk
```

APK в рабочем контейнере проекта не собирается; локальную сборку выполняет разработчик.

### Настройка

В приложении задаются:

- основной URL FastAPI;
- резервный URL FastAPI;
- App PIN;
- MAC-адрес браслета;
- Xiaomi auth key — 32 hex-символа.

App PIN должен совпадать с `APP_PIN` на сервере.

Xiaomi auth key относится к конкретному браслету. Это секрет: не хранить его в Git, README, issue или Logcat.

### Получение Xiaomi auth key

Текущий UI рекомендует внешний инструмент `xiaomi-extractor`. Он не входит в этот репозиторий.

Общий порядок:

1. привязать браслет в Mi Fitness;
2. получить auth/encryption key внешним extractor;
3. проверить, что ключ содержит 32 hex-символа;
4. ввести MAC и ключ в Sleep Monitor;
5. сохранить;
6. перед прямым SPP-сеансом исключить параллельное подключение Mi Fitness.

### Фоновая синхронизация

Используется WorkManager с периодом 60 минут. После загрузки Android также планирует работу после BOOT_COMPLETED.

Временные гарантии запуска не даются: фактическое выполнение зависит от Android/WorkManager.

Persistent queue хранится в app-private storage:

```
filesDir/xiaomi_sync_queue.json
```

Удаление приложения удаляет эту очередь.

## Надёжность доставки

Порядок операций:

```
SPP auth
  ↓
получение activity-файлов
  ↓
локальная очередь
  ↓
POST /login
  ↓
POST /sync для queued days
  ↓
новый SPP-сеанс
  ↓
ACK file IDs
  ↓
очистка queue
```

Очередь очищается только после успешного ACK.

Если сервер недоступен, Android пробует backup URL. Если сервер принял данные, но ACK не прошёл, очередь сохраняется и операция повторяется.

## FastAPI

Backend находится в `app/`.

Основные endpoints:

| Endpoint | Назначение |
|---|---|
| `POST /login` | авторизация по APP_PIN |
| `POST /sync` | автоматическая merge-синхронизация Android |
| `POST /save` | ручное полное сохранение из Web UI |
| `GET /logout` | завершение web-сессии |
| `GET /` | Web UI |

### /sync

Android передаёт:

- date;
- sleep_hours;
- pulse_avg_day;
- pulse_avg_sleep;
- steps_total;
- sleep_awakenings.

`/sync`:

1. проверяет session cookie;
2. читает текущую заметку непосредственно из Obsidian;
3. обновляет машинные показатели;
4. сохраняет пользовательские поля;
5. записывает заметку через Local REST API;
6. перечитывает её;
7. проверяет сохранённый steps_total;
8. возвращает HTTP 200 только после успешной проверки.

Если текущее состояние заметки нельзя достоверно прочитать, endpoint возвращает 502 вместо риска затереть данные.

### /save

`/save` предназначен для ручного редактирования. Он позволяет менять:

- sleep_hours;
- pulse_avg_day;
- pulse_avg_sleep;
- steps_total;
- well_being;
- sleep_quality;
- alco;
- notes.

Новые subjective-поля имеют default 9. `sleep_quality` валидируется в диапазоне 0…9.

## Obsidian

Все чтение и запись дневных заметок проходят через Local REST API.

Путь заметки:

```
55-sleepmon/<YYYY>/<MM>/<YYYY-MM-DD>.md
```

Пример:

```yaml
---
project: "sleepmon"
created: "2026-10-02"
related: "[[55-sleepmon/2026/index-10.md]]"
sleep_hours: 7.5
pulse_avg_day: 68
pulse_avg_sleep: 63
steps_total: 7300
sleep_awakenings: 2
well_being: 9
sleep_quality: 9
alco: false
---

## Заметки

Свободный текст.
```

Автоматическая синхронизация не должна затирать subjective/user-owned поля и свободный текст.

## Docker Compose

Compose поднимает:

- `obsidian`;
- `app`;
- `dbtool`.

Типовой запуск:

```bash
docker compose up -d --build
docker compose ps
docker compose logs -f app
```

Переменные production задаются в `.env`:

```env
APP_PIN=<секрет>
OBSIDIAN_BASE_URL=https://obsidian:27124
OBSIDIAN_API_KEY=<секрет Local REST API>
SLEEPMON_BACKUP_DIR=<каталог backup>
```

`.env` не коммитится.

Для production сначала настроить Obsidian Local REST API и получить его API key, затем указать его в `.env`.

## Deployment

После изменения кода:

### Development

```bash
git status
git add .
git commit -m "описание изменения"
git push
```

### HELOR

```bash
cd /path/to/sleepmon
git pull
docker compose up -d --build app
docker compose ps
docker compose logs -f app
```

Перед deployment всегда проверить, что checkout содержит именно проверенный commit.

Если менялся только `.env`:

```bash
docker compose up -d app
```

## Production checklist

### Android

- [ ] release APK собран локально;
- [ ] build tag соответствует проверенному commit;
- [ ] secure SPP/auth;
- [ ] обычный sync → /sync → ACK;
- [ ] server failover;
- [ ] queue сохраняется при ошибке;
- [ ] queue очищается после успешного ACK;
- [ ] несколько последовательных sync;
- [ ] pulse_avg_day проверен на нескольких днях;
- [ ] pulse_avg_sleep проверен против данных браслета;
- [ ] диагностический raw-file/base64 dump отключён.

### FastAPI

- [ ] production `.env` настроен;
- [ ] Obsidian Local REST API доступен;
- [ ] /login работает;
- [ ] /sync создаёт/обновляет заметку;
- [ ] /sync не затирает пользовательские поля;
- [ ] /sync проверяет сохранённый результат;
- [ ] ошибка Obsidian приводит к 502 без опасного перезаписывания;
- [ ] Docker restart policy работает;
- [ ] backup настроен.

## Диагностика

Android:

```bash
adb logcat -c
adb logcat -s SyncWorker SyncHelper XiaomiBandClassic
```

Ищем последовательность:

```
SPP socket connected
Auth handshake complete
Band offered N file(s)
Server accepted data
Acknowledged N activity file(s) on fresh SPP session
Xiaomi sync session finished
```

Сервер:

```bash
docker compose ps
docker compose logs -f app
```

При HTTP 502 сначала проверить доступность Obsidian Local REST API и логи `app`.

При ошибке secure SPP проверить pairing, отсутствие параллельного соединения Mi Fitness и повторить Sync Now. Insecure SPP не является fallback.

## Ограничения

- Протокол Xiaomi reverse-engineered и не является официальным API.
- Браслет хранит данные ограниченное время; не следует рассчитывать на произвольный старый backfill.
- Некоторые activity-файлы могут быть неполными.
- После перезагрузки браслета secure SPP иногда требует нескольких попыток.
- Удаление Android-приложения удаляет persistent queue.

## Текущее состояние

На 02.10.2026 актуален Android v78.

Архитектура синхронизации и backend-путь подтверждены E2E. v78 ещё требует финального E2E и отключения диагностического raw payload dump перед production.

Подробное актуальное состояние и следующий шаг находятся в `task.md`.
