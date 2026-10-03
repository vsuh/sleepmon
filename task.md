# Sleep Monitor — текущее состояние

Дата актуализации: 02.10.2026

## Цель

Sleep Monitor получает данные Xiaomi Smart Band по Bluetooth Classic SPP, агрегирует их на Android и отправляет дневные показатели в FastAPI. FastAPI изменяет дневные заметки через Obsidian Local REST API.

Основные данные: сон и пробуждения, pulse_avg_day, pulse_avg_sleep, steps_total, а также ручные поля well_being, sleep_quality, alco и заметки.

## Текущий код

Последние code/build commits: f744cda03e8056f6a9df7f98403f04aaf5fd4f92 (release APK filename), 9d345c08585daefe61ae9f71f4ba0eccb66ed090 (release signing property defaults), 85be81f04652c03d841b4c7e9da49c51f35b1d54 (release signing config cleanup), cc5ad10946d6f9938b0ee1ca0d87b87a63e3ac37 (external release signing secrets), 2279e1362dc91d5385f3f1815c7597cf13b8624d (v81), 04adff5573431479cc87a16771128980c24ab243 (local release signing), 92d76d55cfc95321fc482dddc8f1ae384ce1c000 (signing secrets ignored).

Android:
- build tag v81 (02.10.2026);
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
- v78 добавил локальную 7-дневную историю HR для pulse_avg_day;
- v79 удалил сохранение сырых Xiaomi activity-файлов в `filesDir` и base64-вывод payload в Logcat;
- v80 удалил из Logcat sleep `packetTrace` и подробные `type10_*` diagnostics;
- v81 подготовлен для production-log hygiene: убраны hex session-config, fileId/size из manual diagnostics, HTTP response bodies и лишний verbose output.

Последний реальный sleep E2E перед v78 дал sleep HR 63 BPM против значения браслета 63; это подтверждено для v75/v76.

## Что ещё НЕ подтверждено для v80

Реальный v80 E2E 02.10.2026 подтверждён частично:
- v80 APK собран и установлен;
- secure SPP/auth и получение 29 файлов прошли;
- sleep v4/subtype 8: duration 463 min, awakenings 8, pulse_sleep=63 BPM;
- pulse_day=76 BPM для 01.10 и 02.10 по текущей истории HR;
- backend `/login` → `/sync` дополнительно проверен пользователем после запуска backend и работает;
- backup `https://sm.vsuh.duckdns.org:912` в текущем тесте отдавал HTTP 502;
- данные не потерялись: после неуспешного sync ACK не выполнялся, очередь должна сохранять записи.

Не подтверждены:
1. успешное восстановление именно v80 после недоступного сервера → повторный `/sync` → ACK → очистка очереди;
2. production Docker deployment актуального commit на HELOR;
3. release APK/signing и release-конфигурация.

GitHub Actions и GitHub Releases отсутствуют, поэтому автоматической проверки сборки нет.

## Production readiness

### Android APK

Для личного sideload функциональная архитектура близка к рабочей, но v80 пока не считать production APK.

Стоп-факторы:
- нет финального E2E v81 после изменений Logcat/signing;
- release build имеет isMinifyEnabled = false;
- release signing настроен через внешний локальный `../../../_secrets/sleepmon-apk/keystore.properties`; keystore и файл с паролями не хранятся в Git; `keystore.properties`, `*.jks`, `*.keystore` игнорируются;
- Gradle теперь разрешает относительный `storeFile` относительно каталога внешнего `keystore.properties`; если `storeFile` не задан, используется `sleepmon-release.jks`; обязательные password/alias properties проверяются с понятной ошибкой;
- README содержит пошаговую инструкцию создания keystore и `assembleRelease`.
- `versionCode=81`, `versionName=81`, build tag живёт в AppVersion`;
- release APK автоматически называется `sleepmon.apk` вместо стандартного `app-release.apk`.

Перед production APK:
1. проверить `gradlew.bat signingReport`: release должен показывать Config/Store/Alias, а не `null`;
2. собрать release APK локально;
3. проверить подпись APK через `apksigner` из Android SDK Build Tools либо, как минимум, сертификат v1 через `keytool`;
4. установить и провести полный E2E;
5. проверить очередь, server failover и ACK;
6. убедиться, что Logcat не содержит health payload.

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

## Последние изменения
- README обновлён: удалена привязка к текущей версии приложения, добавлено описание API `/login`, `/logout`, `/sync`, `/save` и `/api/storage/sync`, а также настройка `STORAGE_SERVICE` и ежемесячной синхронизации.
- CSS навигации обновлён: фон кнопок прозрачен, иконки увеличены до размера, сопоставимого с высотой контейнера даты, оба элемента центрируются по вертикали.
- В форме навигация по датам оставлена только иконками: подписи «Назад/Вперед» убраны.
- `docker-compose.yml` сохраняет SQLite-файл приложения в `./data:/data`, чтобы база переживала пересоздание контейнера.
- `.env.example` дополнен выбором `STORAGE_SERVICE=sqlite|obsidian` и путём `SLEEPMON_SQLITE_PATH`.
- `app/main.py` переведён на выбранный storage backend: `/save` и `/sync` используют `STORAGE_SERVICE`; добавлен защищённый `POST /api/storage/sync`, а автоматическая сверка предыдущего месяца запускается 1-го числа и повторно проверяется каждые 6 часов.
- В `app/config.py` добавлены `STORAGE_SERVICE` и `SLEEPMON_SQLITE_PATH` для выбора backend через `.env`.
- Добавлен `app/storage.py`: configurable storage `sqlite`/`obsidian`, SQLite-хранилище и ежемесячная reconciliation-синхронизация между двумя backend-сервисами; выбранный в `.env` backend является источником истины при наличии записи с обеих сторон.
- Уточнён комментарий `/save`: это snapshot-aware merge, а не полная перезапись формы.
- `/save` теперь также прерывается с HTTP 502, если текущую заметку нельзя прочитать: при навигации или сохранении неизвестное состояние Obsidian не может быть перезаписано значениями формы по умолчанию.
- README переписан для пользователя: описаны ежедневная работа с формой, новая навигация и безопасное сохранение, Android-синхронизация, обновление приложения через build-release-install.bat и основные действия при сбоях; разработческие инструкции убраны из README.
- Путь в release-скрипте скорректирован под текущий Gradle: готовый APK берётся из `android_sync_app/app/build/outputs/sleepmon/sleepmon.apk`.
- CSS формы обновлён под новую навигацию: одна дата по центру, кнопки с SVG-стрелками по краям; на узком экране текст кнопок скрывается, остаются иконки.
- `/save` теперь перед записью перечитывает актуальную заметку и использует `original_*` snapshot формы: если поле не менялось на экране, текущее значение из Obsidian сохраняется; изменённое поле сохраняется явно. Кнопки «Назад/Вперед» сначала проходят через `/save`, затем открывают соседнюю дату.
- Web-форма переделана: вместо вкладок с датами одна дата с кнопками «Назад/Вперед» с SVG-иконками; пояснение перенесено в `title` заголовка; «Как спалось» сразу после «Сон`; навигационные кнопки и «Сохранить» отправляют одну и ту же форму.

- `android_sync_app/build-release-install.bat` добавлен: последовательно выполняет `assembleRelease` → проверку `sleepmon.apk` через `apksigner` → `adb install -r`; при любой ошибке следующий шаг не запускается.

## Правила разработки

- APK не собирать в рабочем контейнере; сборку выполняет пользователь локально.
- После изменения Android-кода повышать AppVersion.
- После каждого изменения кода обновлять этот файл.
- Не менять secure SPP без реального logcat.
- ACK выполнять только после успешного server sync.
- Документация описывает текущее поведение.
- Xiaomi auth key, APP_PIN и Obsidian API key не хранить в Git/README/Logcat.

## Ближайшие шаги

1. E2E v80: сервер недоступен → очередь → восстановление → повтор → ACK → очистка очереди.
2. Проверить несколько последовательных дней и pulse_avg_day/pulse_avg_sleep.
3. Обновить production HELOR до проверенного commit.
4. Проверить release signing через внешний `_secrets/sleepmon-apk`, собрать подписанный release APK и провести финальный E2E.
5. После успешного E2E считать конфигурацию готовой к эксплуатации.
