---
project: Монитор здоровья с браслета
created: "2026-09-26"
related: "[[10-projects/index|index]]"
title: Sleep Monitor
---

# Sleep Monitor

Личная система дневника сна, пульса, активности и самочувствия в Obsidian.

Основной источник данных — **Xiaomi Smart Band**. Android Companion App напрямую соединяется с браслетом по Bluetooth Classic SPP, получает накопленные activity-файлы, агрегирует дневные показатели и отправляет их на FastAPI. FastAPI записывает данные в Obsidian через Local REST API.

Проект однопользовательский. Аналитика и графики находятся за пределами приложения и могут строиться в Obsidian/Dataview.

**Текущая Android-версия: v40 (26.09.2026).**

---

## Архитектура

```
Xiaomi Smart Band
      │ Bluetooth Classic / secure RFCOMM (SPP)
      ▼
Android Companion App
      │ persistent queue
      │ POST /login → session cookie
      │ POST /sync
      ▼
FastAPI :8000
      │ HTTPS
      │ Obsidian Local REST API
      ▼
Obsidian in Docker
      │
      ▼
/app/vault
      │
      ▼
/path-to-projects/syncthing/AI.obsdn
```

Принципиальные правила:

- Android использует только **secure RFCOMM/SPP**.
- После загрузки activity-файлов download-сеанс закрывается.
- После успешного `/sync` открывается новый SPP-сеанс для ACK файлов.
- Файл считается подтверждённым браслету только после успешной обработки сервером и ACK.
- При недоступном сервере данные остаются в `filesDir/xiaomi_sync_queue.json`.
- Для дневного итога шагов используется Xiaomi daily summary `version=5`, если он доступен.
- Субъективные поля `well_being`, `sleep_quality`, `alco` и свободный текст не перезаписываются автоматической синхронизацией.

---

## Структура репозитория

```
sleepmon/
├── app/                         # FastAPI + HTML UI
├── android_sync_app/            # Android Companion App
├── docker-compose.yml
├── .env.example
├── task.md                      # рабочая задача и техническая история
└── README.md                    # эксплуатационная документация
```

---

# 1. Где ведётся разработка и где работает продукт

Это **не два независимых проекта**.

GitHub `vsuh/sleepmon` — центральный репозиторий. Дерево проекта для разработки находится внутри Obsidian vault:

```
C:\AI.obsdn\10-projects\sleep-monitor
```

Именно в этом checkout выполняется разработка и изменения отражаются в GitHub.

На Linux-сервере **HELOR** находится отдельный production checkout:

```
services/opt/sleepmon
```

Этот каталог получает проект из GitHub и является **продуктовой средой**, в которой запускается Docker Compose.

Типовой цикл:

```
Obsidian vault checkout
C:\AI.obsdn\10-projects\sleep-monitor
        │
        ├── изменение
        ├── git add
        ├── git commit
        └── git push
                │
                ▼
        GitHub: vsuh/sleepmon
                │
                ▼
HELOR production checkout
services/opt/sleepmon
        │
        ├── git pull
        └── docker compose up -d --build app
```

**Важно:** перед сборкой на HELOR обязательно получить свежий commit через `git pull`. Иначе Docker может успешно собрать старую версию проекта.

---

# 2. Первичное развёртывание на HELOR

Если production checkout ещё не создан:

```bash
cd /path-2-prj/services/opt
git clone https://github.com/vsuh/sleepmon.git sleepmon
cd sleepmon
```

Если checkout уже существует:

```bash
cd /path-2-prj/services/opt/sleepmon
git pull
```

Создать рабочую конфигурацию:

```bash
cp .env.example .env
```

Минимальный `.env`:

```env
APP_PIN=<ваш PIN>
OBSIDIAN_BASE_URL=https://obsidian:27124
OBSIDIAN_API_KEY=<ключ Local REST API>
```

`APP_PIN` — PIN веб-приложения и одновременно пароль, который Android Companion App передаёт на `/login`.

**Не путать два ключа:**

- `APP_PIN` — секрет авторизации Sleep Monitor API;
- `OBSIDIAN_API_KEY` — секрет плагина Obsidian Local REST API;
- Xiaomi **auth key** — 32 hex-символа для Bluetooth-аутентификации конкретного браслета. Он хранится на телефоне и вводится в Android-приложение.

Запуск:

```bash
docker compose up -d --build
```

Проверка:

```bash
docker compose ps
docker compose logs -f app
```

Веб-интерфейс:

```
http://<IP-HELOR>:8000
```

---

# 3. Настройка Obsidian и Local REST API

Compose запускает два сервиса:

- `obsidian` — Obsidian/Electron внутри контейнера;
- `app` — FastAPI.

В текущем `docker-compose.yml` vault внутри контейнера доступен по пути `/app/vault`, а на хосте это `/path-to-projects/syncthing/AI.obsdn`.

```text
host:      /path-to-projects/syncthing/AI.obsdn
container: /app/vault

obsidian:
  volume:
    /path-to-projects/syncthing/AI.obsdn:/app/vault:rw
  ports:
    8080:8080
    27124:27124
```

Порт `8080` используется для VNC-доступа к Obsidian при первоначальной настройке.

Порядок:

1. Запустить `obsidian`.
2. Через VNC/noVNC открыть Obsidian.
3. Открыть vault, смонтированный внутри контейнера как `/app/vault`.
4. Установить/включить Community Plugin **Local REST API**.
5. Настроить Local REST API на порт `27124`.
6. Bind Address должен быть `0.0.0.0`, чтобы контейнер `app` мог обратиться к нему.
7. Сгенерировать API key плагина.
8. Записать этот ключ в production `.env` как `OBSIDIAN_API_KEY`.
9. Перезапустить `app` после изменения `.env`:

```bash
docker compose up -d --build app
```

Внутри Docker-сети FastAPI обращается к Obsidian по:

```
https://obsidian:27124
```

FastAPI не должен напрямую изменять markdown-файлы vault: запись выполняется через Local REST API, чтобы Obsidian оставался источником истины для своих данных и кеша.

---

# 4. API приложения синхронизации

Это API **самого FastAPI приложения Sleep Monitor**, а не Xiaomi API и не Obsidian API.

Базовый URL:

```
http://<server>:8000
```

## `POST /login`

Авторизация Android и Web UI.

Параметр формы:

| Параметр | Тип | Назначение |
|---|---|---|
| `pin` | string | значение `APP_PIN` |

При правильном PIN сервер устанавливает cookie:

```
session_pin=<APP_PIN>
```

Android сохраняет значение `Set-Cookie` и передаёт его следующим запросом.

Пример:

```bash
curl -i -c cookies.txt \
  -X POST http://<server>:8000/login \
  -d 'pin=<ваш PIN>'
```

При неправильном PIN сервер возвращает HTML страницы входа с сообщением об ошибке.

## `POST /sync`

Основной endpoint автоматической синхронизации Android.

Авторизация: cookie `session_pin`, полученная через `/login`.

Content-Type:  HTML form encoding (`application/x-www-form-urlencoded`).

Параметры:

| Параметр | Тип | Default | Назначение |
|---|---:|---:|---|
| `date` | string | — | дата `YYYY-MM-DD` |
| `sleep_hours` | float | 0 | длительность сна |
| `pulse_avg_day` | int | 0 | средний пульс в период бодрствования |
| `pulse_avg_sleep` | int | 0 | средний пульс во время сна |
| `steps_total` | int | 0 | итоговое число шагов |
| `sleep_awakenings` | int | 0 | число отдельных эпизодов пробуждения |

Пример:

```bash
curl -b cookies.txt \
  -X POST http://<server>:8000/sync \
  -d 'date=2026-09-26' \
  -d 'sleep_hours=7.5' \
  -d 'pulse_avg_day=68' \
  -d 'pulse_avg_sleep=58' \
  -d 'steps_total=7300' \
  -d 'sleep_awakenings=2'
```

Успешный ответ:

```json
{"status":"ok","date":"2026-09-26","steps_total":7300}
```

Поведение `/sync`:

- читает текущую запись непосредственно из Obsidian;
- `steps_total` обновляется всегда;
- `pulse_avg_day` и `pulse_avg_sleep` обновляются;
- `sleep_hours` и `sleep_awakenings` заполняются только если соответствующие данные ещё не были записаны;
- `well_being`, `sleep_quality`, `alco` и свободный текст сохраняются;
- после записи заметка перечитывается;
- сохранённый `steps_total` проверяется;
- при невозможности безопасно прочитать текущую заметку сервер возвращает `502`, чтобы не рисковать потерей ручных данных.

Основные ошибки:

- `401` — нет корректной сессии;
- `502` — Obsidian недоступен, запись не выполнена или результат записи не удалось проверить.

## `POST /save`

Ручное полное сохранение дневной записи из Web UI.

Параметры:

```
date
sleep_hours
pulse_avg_day
pulse_avg_sleep
steps_total
well_being
sleep_quality
alco
notes
```


`/save` предназначен для ручного редактирования и, в отличие от `/sync`, может изменять субъективные поля.

## `GET /logout`

Удаляет cookie веб-сессии и перенаправляет на `/login`.

## `GET /`

Web UI дневной записи. Требует авторизации.

---

# 5. Схема дневной записи

Файлы дневника:

```
55-sleepmon/<YYYY>/<MM>/<YYYY-MM-DD>.md
```

Пример:

```
55-sleepmon/2026/09/2026-09-26.md
```

Пример frontmatter:

```yaml
---
project: "sleepmon"
created: "2026-09-26"
related: "[[55-sleepmon/2026/index-09.md]]"
sleep_hours: 7.5
pulse_avg_day: 68
pulse_avg_sleep: 58
steps_total: 7300
sleep_awakenings: 2
well_being: 7
sleep_quality: 8
alco: false
---

## Заметки

Свободный текст.
```

### Поля пользователя

- `well_being` — субъективное самочувствие `0…9`;
- `sleep_quality` — субъективное качество сна, целое `0…9`;
- `alco` — был ли алкоголь;
- текст примечания `## Заметки`.

Для новой записи `well_being=9,sleep_quality=9,alco=false`. Автоматическая синхронизация не меняет эти поля.

---

# 6. Установка Android Companion App

APK собирается из каталога:

```
android_sync_app/
```


Windows:

```bat
cd android_sync_app
gradlew.bat :app:assembleDebug
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

После установки нужно:

1. Сопрячь браслет с Android в системных настройках Bluetooth (это возможно после установки mi fitness, сопряжения браслета и удаления mi fitness).
2. Запустить Sleep Monitor Sync.
3. Предоставить требуемые разрешения (Bluetooth).
4. Открыть **⚙ Настройки**.
5. Заполнить параметры сервера и Xiaomi Band (адрес сервера синхронизации, резервный адрес, app PIN, mac адрес и ключ авторизации браслета).
6. Сохранить ключ браслета.
7. Нажать **Sync Now** и проверить logcat.

---

# 7. Настройки Android APK

Экран **⚙ Настройки** содержит две группы.

## Сервер синхронизации

### Server URL (основной)

Адрес FastAPI:

```
http://<server>:8000
```

Текущий встроенный default:

```
http://192.168.2.2:8000
```

### Server URL (резервный)

Резервный адрес FastAPI.

Текущий встроенный default:

```
https://your.ddns.org:999
```

Если основной сервер не отвечает, приложение пробует резервный.

### App PIN

Должен совпадать с `APP_PIN` на сервере.

Текущий встроенный default:

```
APP_PIN=<ваш PIN>
```

Если `APP_PIN` на сервере изменён, это значение также нужно изменить в APK через **Настройки → App PIN**.

**В Android нет отдельного API key для `/sync`: приложение сначала делает `POST /login` с этим PIN, получает session cookie и использует её для `/sync`.**

---

## Xiaomi Band

### MAC-адрес

Bluetooth MAC конкретного браслета.

Его нужно вводить для того устройства, с которым Android уже выполнит Bluetooth pairing.

### Ключ авторизации

Поле требует **ровно 32 hex-символа**:

```
0123456789abcdef0123456789abcdef
```

Это 16-байтовый Xiaomi pairing/auth secret конкретного браслета.

В коде приложения ключ нормализуется: начальный `0x` допускается и удаляется.

### Как получить Xiaomi auth key

В текущем приложении текст подсказки на экране настроек рекомендует использовать **xiaomi-extractor**. Сам extractor не является частью этого репозитория, поэтому его инструкция и версия должны проверяться отдельно.

Общий надёжный принцип:

1. Сначала привязать браслет в официальном **Mi Fitness**.
2. Получить с помощью [xiaomi-extractor](https://github.com/piotrmachowski/xiaomi-cloud-tokens-extractor) encription key (TOKEN).
3. Убедиться, что это значение содержит 32 hex-символа.
4. Ввести его в **Настройки → Xiaomi Band → Ключ авторизации**.
5. Ввести MAC этого же браслета.
6. Нажать **Сохранить ключ браслета**.
7. Перед подключением удалить Mi Fitness, чтобы оно не удерживало Bluetooth-соединение.


**Encription key (TOKEN) — секрет, аналогичный паролю. Не помещать его в Git, README, issue или публичные логи.** При отвязке/перепривязке устройства к Mi Fitness, ключ пересоздается тогда его нужно получить заново. 

---

# 8. Первый запуск синхронизации

После настройки:

1. Убедиться, что браслет включён.
2. Убедиться, что он сопряжён с Android.
3. Удалить Mi Fitness.
4. Открыть Sleep Monitor Sync.
5. Проверить **Настройки**.
6. Запустить logcat
```
adb logcat -c && adb logcat -s SyncWorker SyncHelper XiaomiBandClassic
```
1. Нажать **Sync Now**.

Нормальная последовательность в logcat:

```
SPP socket connected
Auth handshake complete
Band offered N file(s)
Server accepted data ... (HTTP 200)
Acknowledged N activity file(s) on fresh SPP session
═══ Xiaomi sync session finished
```

В начале операции должен быть виден build tag, например:

```
SyncHelper: === v40 (26.09.2026) - SyncHelper ===
```

---

# 9. Фоновая синхронизация

Android использует WorkManager.

Задача восстанавливается после перезагрузки телефона через BootReceiver.

Фактическое время запуска зависит от ограничений Android/WorkManager. Расписание не следует воспринимать как гарантию запуска с точностью до минуты.

При недоступном сервере данные помещаются в:

```
filesDir/xiaomi_sync_queue.json
```

Очередь переживает перезапуск приложения, но **не переживает удаление приложения**, поскольку находится в app-specific storage.

---

# 10. Деплой после изменения кода

На компьютере разработки:

```text
cd /d C:\AI.obsdn\10-projects\sleep-monitor
```

После изменения:

```bash
git status
git add .
git commit -m "описание изменения"
git push
```

На HELOR:

```bash
cd /path-2-prj/services/opt/sleepmon
git pull
docker compose up -d --build app
```

Проверка:

```bash
docker compose ps
docker compose logs -f app
```

Если менялась только конфигурация `.env`, пересобрака образа не обязательна; достаточно перезапустить нужный сервис:

```bash
docker compose up -d app
```

Если менялись Dockerfile, Python, templates или другие файлы, влияющие на образ, использовать:

```bash
docker compose up -d --build app
```


---

# 11. Обновление APK

После изменения Android-кода:

1. повысить `APP_BUILD_TAG`;
2. обновить `task.md`;
3. собрать APK локально;
4. установить APK через `adb install -r`;
5. выполнить E2E-синхронизацию;
6. зафиксировать результат теста с build tag.

---

# 12. Диагностика сервера

Логи:

```bash
cd /path-2-prj/services/opt/sleepmon
docker compose logs -f app
```

Статус:

```bash
docker compose ps
```

Если app не может обратиться к Obsidian, проверить:

- контейнер `obsidian`;
- Local REST API;
- bind address `0.0.0.0`;
- `OBSIDIAN_BASE_URL`;
- `OBSIDIAN_API_KEY`;
- Docker network.

Если `/sync` возвращает `502`, сначала проверить доступность Obsidian и логи `app`.

---

# 13. Диагностика secure SPP

Ошибка:

```
Secure SPP connect() failed: read failed, socket might closed or timeout
```

не обязательно означает окончательный отказ. Приложение делает несколько secure RFCOMM-попыток.

Порядок:

1. проверить Bluetooth pairing;
2. исключить параллельное соединение Mi Fitness;
3. повторить **Sync Now**;
4. при необходимости перезапустить браслет;
5. смотреть logcat до `Auth handshake complete` или окончательной ошибки.

Возвращать insecure SPP как обходной путь нельзя: insecure fallback удалён после диагностики v39.

---

# 14. Ограничения

- Xiaomi не предоставляет этому проекту официальный публичный API для прямого чтения всех данных браслета; Android использует reverse-engineered Bluetooth SPP протокол.
- Браслет хранит накопленные данные ограниченное время. Практическое тестирование показало примерно 7–10 дней доступных данных после повторного сопряжения; это не гарантия для каждого типа файла.
- Не следует рассчитывать на произвольный backfill очень старых данных.
- Некоторые Xiaomi activity-файлы могут быть неполными/слишком короткими.
- После перезагрузки браслета secure SPP иногда требует несколько попыток подключения.
- Удаление Android-приложения удаляет persistent queue.
- Xiaomi auth key зависит от конкретного устройства и состояния его привязки.

---

# 15. Связанные документы

- `task.md` — текущая задача, архитектурные решения, E2E-результаты и история принципиальных изменений.
- `.env.example` — параметры серверной конфигурации.
- `android_sync_app/` — исходный код Android Companion App.
