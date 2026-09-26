---
project: Монитор здоровья с браслета
created: "2026-09-26"
related: "[[10-projects/index|index]]"
title: Sleep Monitor
---

# Sleep Monitor

Личный сервис для ведения дневника сна, пульса, активности и самочувствия в Obsidian.

Основной источник данных — **Xiaomi Smart Band**, с которым Android Companion App соединяется напрямую по Bluetooth SPP. Сервер принимает агрегированные дневные данные через FastAPI и записывает их в Obsidian через Local REST API.

Проект однопользовательский. Аналитика и графики не входят в приложение: для них используется Obsidian/Dataview.

**Текущая Android-версия: v40 (26.09.2026).**

---

## Как это работает

```
┌──────────────────────┐
│ Xiaomi Smart Band    │
│ activity / sleep /   │
│ heart rate / summary │
└──────────┬───────────┘
           │ Bluetooth SPP
           ▼
┌──────────────────────┐
│ Android Companion    │
│ secure RFCOMM        │
│ aggregation          │
│ persistent queue     │
└──────────┬───────────┘
           │ POST /sync
           ▼
┌──────────────────────┐
│ FastAPI app :8000    │
└──────────┬───────────┘
           │ HTTPS
           │ Local REST API
           ▼
┌──────────────────────┐
│ Obsidian             │
│ Local REST API       │
└──────────┬───────────┘
           ▼
     Syncthing vault
```

### Принципиально

- Android использует **только secure RFCOMM/SPP**. 
- После загрузки activity-файлов Android закрывает download-сеанс.
- После успешного `/sync` открывается **новый SPP-сеанс** для ACK файлов.
- Файл не считается подтверждённым до успешного ACK.
- При недоступном сервере данные остаются в persistent queue и могут быть отправлены позже.
- Для дневного итога шагов используется Xiaomi activity summary `version=5`, если он доступен.
- Отдельные слишком короткие activity-файлы пропускаются.

---

## Репозиторий

```
sleepmon/
├── app/                         # FastAPI + HTML UI
├── android_sync_app/            # Android Companion App
├── docker-compose.yml           # серверная инфраструктура
├── .env.example                 # пример конфигурации
├── task.md                      # текущая задача, план и технические решения
└── README.md                    # эксплуатационная документация
```

Android-код находится в `android_sync_app/`.

---

## Дневные заметки

Основной путь:

```
55-sleepmon/<YYYY>/<MM>/<YYYY-MM-DD>.md
```

Пример:

```
55-sleepmon/2026/09/2026-09-26.md
```

Типичные поля:

```yaml
---
project: "sleepmon"
created: "2026-09-26"
related: "[[55-sleepmon/2026/index-09.md]]"
sleep_hours: 7.5
pulse_avg_day: 68
pulse_avg_sleep: 58
steps_total: 7300
sleep_light_min: 210
sleep_deep_min: 85
sleep_rem_min: 95
sleep_awake_min: 15
well_being: 7
alco: false
---

## Заметки

Свободный текст.
```

### Важное правило данных

Android-синхронизация не должна уничтожать ручные данные пользователя:

- `well_being`;
- `alco`;
- свободный текст заметки.

---

## Сервер

Сервер состоит из двух основных контейнеров:

1. **`obsidian`** — реальный Obsidian/Electron внутри Xvfb с плагином Local REST API.
2. **`app`** — FastAPI-приложение и HTML-интерфейс.

Vault синхронизируется через Syncthing.

### Почему запись идёт через Local REST API

Не следует писать markdown-файлы vault напрямую из FastAPI. Obsidian должен сам обработать изменения через свой API, чтобы его live-кеш и метаданные оставались согласованными.

### Docker Compose

Ключевые переменные:

```env
APP_PIN=<PIN>
OBSIDIAN_BASE_URL=https://obsidian:27124
OBSIDIAN_API_KEY=<API key Local REST API>
```

Запуск:

```bash
docker compose up -d --build
```

Внутренний адрес Obsidian:

```
https://obsidian:27124
```

Local REST API должен слушать `0.0.0.0:27124`, а не только `127.0.0.1`.

Первичная настройка Obsidian выполняется через VNC/noVNC, если образ/конфигурация требует ручного открытия vault и настройки Community Plugin Local REST API.

---

## Web UI

Откройте:

```
http://<IP_СЕРВЕРА>:8000
```

Доступ защищён общим PIN.

Интерфейс позволяет:

- открыть сегодняшнюю или прошлую дневную запись;
- вручную изменить показатели;
- задать `well_being`;
- указать `alco`;
- отредактировать свободные заметки.

Ручное сохранение выполняется через `/save`.

---

## Android Companion App

Каталог:

```
android_sync_app/
```

Приложение предназначено прежде всего для прямой синхронизации с Xiaomi Smart Band.

### Синхронизация с браслетом

Последовательность:

1. открыть secure SPP/RFCOMM;
2. выполнить Xiaomi auth handshake;
3. получить список предложенных activity-файлов;
4. запросить файлы;
5. разобрать их и агрегировать дневные данные;
6. положить данные и идентификаторы файлов в persistent queue;
7. отправить дневные данные на `/sync`;
8. после успешного ответа сервера открыть свежий SPP-сеанс;
9. подтвердить полученные activity-файлы;
10. завершить сеанс и записать в logcat:
   `═══ Xiaomi sync session завершён`.

### Persistent queue

Очередь хранится в:

```
filesDir/xiaomi_sync_queue.json
```

Она содержит дневные данные и идентификаторы activity-файлов.

Смысл очереди:

- сервер временно недоступен → данные не теряются;
- приложение можно перезапустить → очередь остаётся;
- HTTP 200 без последующего ACK не считается завершённым циклом;
- при успешном ACK соответствующие элементы можно удалить из очереди.

Очередь является app-specific storage и не переживает удаление приложения.

### Серверы

Android поддерживает основной и резервный адрес сервера. Если основной недоступен, приложение может перейти к резервному.

### Фоновая синхронизация

Для фонового запуска используется WorkManager; после перезагрузки телефона задача восстанавливается через BootReceiver.

Точное фактическое расписание фоновой работы определяется ограничениями Android/WorkManager и не должно восприниматься как гарантия запуска с точностью до минуты.

---

## Сборка Android


Windows:

```bat
cd android_sync_app
gradlew.bat assembleDebug
```

Linux/macOS/WSL:

```bash
cd android_sync_app
./gradlew assembleDebug
```

APK:

```
android_sync_app/app/build/outputs/apk/debug/app-debug.apk
```

Установка:

```bash
adb install -r app-debug.apk
```

После любого изменения Android-кода APK необходимо пересобрать перед тестом.

---

## Диагностика Android

Основные теги logcat:

```bash
adb logcat -s SyncWorker SyncHelper XiaomiBandClassic
```

В начале операции должен быть виден build tag, например:

```
SyncHelper: === v40 (26.09.2026) - SyncHelper ===
```

Успешный SPP-сеанс содержит примерно:

```
SPP socket connected
Auth handshake complete
Band offered N file(s)
```

Успешная доставка:

```
Server accepted data ... (HTTP 200)
```

Успешный ACK:

```
Acknowledged N activity file(s) on fresh SPP session
```

Завершение:

```
═══ Xiaomi sync session завершён
```

### Если secure SPP не подключается

Возможна ошибка вида:

```
Secure SPP connect() failed: read failed, socket might closed or timeout
```

Приложение делает несколько secure RFCOMM-попыток. Поэтому единичный failure ещё не означает окончательный отказ.

Практический порядок диагностики:

1. проверить, что браслет включён и доступен;
2. исключить параллельное Bluetooth-подключение к нему;
3. повторить `Sync Now`;
4. при необходимости перезапустить браслет;
5. смотреть полный logcat до строк `Auth handshake complete` или окончательной ошибки.

Не следует возвращать insecure SPP только из-за единичных ошибок secure подключения: insecure fallback уже удалён после диагностики v39.

---

## Диагностика сервера

Логи приложения:

```bash
docker compose logs -f app
```

Проверка контейнеров:

```bash
docker compose ps
```

Проверка доступа app → Obsidian:

```bash
docker compose exec app <команда проверки HTTPS-доступа к https://obsidian:27124>
```

Если появляется `Connection refused`, в первую очередь проверить:

- контейнер `obsidian`;
- Local REST API;
- Bind Address `0.0.0.0`;
- API key;
- внутреннюю docker-сеть.

---

## Деплой: два git checkout

Vault и серверный deploy — независимые checkout одного репозитория `vsuh/sleepmon`.

После изменения кода:

```
vault checkout
    │
    ├── git add
    ├── git commit
    └── git push
            │
            ▼
server deploy checkout
    │
    ├── git pull
    └── docker compose up -d --build app
```

Если сделать `docker compose build` в старом deploy checkout без `git pull`, сервер может корректно собрать **старый код**.

---

## Известные ограничения

- Xiaomi не предоставляет проекту официальный публичный API для прямого получения всех данных; текущая реализация использует протокол Bluetooth SPP.
- Браслет хранит данные ограниченное время. Практическое тестирование с Mi Fitness показало примерно 7–10 дней доступных данных после повторного сопряжения; это не является гарантией для каждого типа файла.
- Не следует рассчитывать на произвольный backfill очень старых данных непосредственно с браслета.
- Отдельные Xiaomi activity-файлы могут быть неполными/слишком короткими.
- Android может потребовать несколько secure SPP-попыток перед успешным подключением.
- Удаление Android-приложения удаляет его app-specific persistent queue.

---

## Документация проекта

`task.md` — основной рабочий документ. Там зафиксированы:

- точная задача;
- план разработки;
- текущая архитектура;
- принципиальные результаты первых итераций;
- результаты исследования хранения данных;
- правила дальнейших изменений;
- текущий E2E-статус.

