# Sleep Monitor — текущее состояние

Дата актуализации: 02.10.2026

## Цель

Sleep Monitor получает данные Xiaomi Smart Band по Bluetooth Classic SPP, агрегирует их на Android и отправляет дневные показатели в FastAPI. FastAPI изменяет дневные заметки через Obsidian Local REST API.

Основные данные: сон и пробуждения, pulse_avg_day, pulse_avg_sleep, steps_total, а также ручные поля well_being, sleep_quality, alco и заметки.

## Текущий код

Последний commit: 4e578b4208baaed799659495dc28eea33fdadeb9.

Android:
- build tag v78 (02.10.2026);
- secure RFCOMM/SPP и Xiaomi auth;
- получение activity-файлов и daily summary;
- persistent queue;
- /login → /sync → отдельный SPP-сеанс ACK;
- WorkManager + запуск после boot;
- primary/backup server;
- экран состояния очереди;
- 7-дневная история HR для расчёта дневного пульса.

Backend:
- FastAPI /login, /sync, /save, /logout;
- запись только через Obsidian Local REST API;
- /sync читает текущую заметку напрямую, сохраняет пользовательские поля и перечитывает результат;
- Docker Compose: app + Obsidian + dbtool.

## Что подтверждено E2E

- secure SPP/auth работают;
- activity-файлы получаютcя;
- daily summary v5 используется для authoritative steps_total;
- /sync отвечает HTTP 200;
- после успешного /sync выполняется отдельный ACK-сеанс;
- persistent queue сохраняется при ошибке сервера/ACK;
- сон v4/subtype 8 разбирается;
- sleep HR берётся из собственного HR-блока sleep-файла внутри bed..wake;
- type=10 больше не используется как источник sleep HR;
- v78 добавил локальную 7-дневную историю HR для pulse_avg_day.

Последний реальный sleep E2E перед v78 дал sleep HR 63 BPM против значения браслета 63; это подтверждено для v75/v76.

## Что ещё НЕ подтверждено для v78

Нет реального E2E-результата именно v78 после изменения расчёта pulse_avg_day.

Не подтверждены:
1. v78 APK реально собран и установлен;
2. новый pulse_avg_day проверен на нескольких последовательных синхронизациях;
3. восстановление после недоступного сервера и последующий ACK проверены на актуальном коде;
4. production Docker deployment актуального commit проверен на HELOR.

GitHub Actions и GitHub Releases отсутствуют, поэтому автоматической проверки сборки нет.

## Production readiness

### Android APK

Для личного sideload функциональная архитектура близка к рабочей, но v78 пока не считать production APK.

Стоп-факторы:
- нет E2E v78;
- актуальный код сохраняет сырые Xiaomi activity-файлы в filesDir/raw_band_files и пишет их в Logcat как base64;
- release build имеет isMinifyEnabled = false;
- release signing/distribution не настроены;
- versionCode остаётся 1, build tag живёт отдельно в AppVersion.

Перед production APK:
1. удалить/отключить raw-file dump и подробную payload-диагностику;
2. собрать release APK локально;
3. установить и провести полный E2E;
4. проверить очередь, server failover и ACK;
5. убедиться, что Logcat не содержит health payload.

### FastAPI app

Backend архитектурно готов к production deployment, но текущий production runtime этим аудитом не подтверждён.

Перед выкладкой:
1. git pull до проверенного commit;
2. .env с реальными секретами;
3. Obsidian Local REST API;
4. docker compose up -d --build app;
5. /login → /sync → проверка заметки;
6. /save и сохранение пользовательских полей;
7. /sync при недоступном Obsidian должен возвращать 502 и не затирать данные;
8. проверить backup dbtool/volume.

## Правила разработки

- APK не собирать в рабочем контейнере; сборку выполняет пользователь локально.
- После изменения Android-кода повышать AppVersion.
- После каждого изменения кода обновлять этот файл.
- Не менять secure SPP без реального logcat.
- ACK выполнять только после успешного server sync.
- Документация описывает текущее поведение.
- Xiaomi auth key, APP_PIN и Obsidian API key не хранить в Git/README/Logcat.

## Ближайшие шаги

1. Убрать production-опасный raw-file dump/payload logging.
2. Собрать следующий release APK локально.
3. E2E: обычная синхронизация → /sync → ACK.
4. E2E: сервер недоступен → очередь → восстановление → повтор → ACK → очистка очереди.
5. Проверить несколько последовательных дней и pulse_avg_day/pulse_avg_sleep.
6. Обновить production HELOR до проверенного commit.
7. После успешного E2E считать конфигурацию готовой к эксплуатации.
