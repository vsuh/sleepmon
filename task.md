# Sleep Monitor — текущее состояние

Дата актуализации: 05.10.2026

## Текущая настройка синхронизации

- Сверка SQLite и Obsidian теперь выполняется автоматически каждый час: предыдущий месяц сверяется целиком, текущий — с 1-го числа по сегодняшний день включительно.
- Hourly sync теперь изолирует текущий и предыдущий месяцы: сбой одного месяца/зависимого backend не прерывает обработку второго; после завершения обеих попыток ошибка любого месяца пробрасывается в hourly loop, поэтому вместо ложного «completed successfully» выполняется повтор через 60 секунд.
- Поле «Самочувствие» в web-форме ограничено диапазоном 0–9.
- Если backend временно недоступен (например, Obsidian ещё запускается), hourly loop не падает: пишет предупреждение и повторяет попытку через 60 секунд; после успешной сверки возвращается к часовому интервалу. Runtime 04.10.2026 подтвердил, что при старте app раньше Obsidian возникал `Connection refused`, но из-за перехвата ошибок внутри `sync_recent_months()` раньше ошибочно логировалось успешное завершение; это исправлено.
- `/api/storage/sync` доступен как GET и POST; на форме под заголовком добавлена ссылка для ручного запуска.
- В навигации формы после кнопки «Вперед» добавлена кнопка «Сегодня» с иконкой ◴; `/save` обрабатывает её переходом на текущую дату.
- `/favicon.ico` отдаёт 32/16px ICO с иконкой часов ◴ и кэшированием на сутки.
- Тёмная тема формы уже была активной; CSS навигации обновлён под четыре элемента (Назад → дата → Вперед → Сегодня) и сохранён тёмный стиль.
- Кнопки навигации по датам визуально увеличены: теперь это заметные элементы 64×56 px (60×56 px на узком экране) с тёмным полупрозрачным фоном и рамкой; иконки остаются крупными и центрированными.
- HTML формы явно подключает `/favicon.ico` как favicon.
- Исправлен встроенный base64 payload ICO; заголовок и размеры favicon проверены после обновления.
- Runtime-проверка нового hourly sync и ссылки ещё не выполнена; отдельно проверено по логам, что временная недоступность Obsidian должна обрабатываться повторными попытками без падения приложения.

## Проверка Edge

- Пользователь сообщил, что кнопки формы не отображаются в Microsoft Edge.
- Навигационные SVG заменены на текстовые glyph-иконки, а CSS получил явные appearance, размеры, цвет и line-height для Edge.
- Фон кнопок остаётся прозрачным.
- Runtime-проверка после обновления ещё не выполнена; перед проверкой нужен Ctrl+F5.

## Цель

Sleep Monitor получает данные Xiaomi Smart Band по Bluetooth Classic SPP, агрегирует их на Android и отправляет дневные показатели в FastAPI. FastAPI изменяет дневные заметки через Obsidian Local REST API.

Основные данные: сон и пробуждения, pulse_avg_day, pulse_avg_sleep, steps_total, а также ручные поля well_being, sleep_quality, alco и заметки.

## Текущий код

Последние code/build commits: 3d356c7f7e9a7aada3451b0d4c2c965cd3818ab1 (v82), 4714cc096a582fa2e322c55c4d0596b931b7e38f4 (WorkManager KEEP), 1e11b9079d4eb63e1b3b7ffdd070501684cda6f4 (ошибки recent sync возвращаются в hourly retry), be90675dc85abf4d3eee688519da1ebed7b10bdf7 (изоляция ошибок current/previous sync), 6b4042bedf3c0778d73429673b579b01f056e892 (увеличены кнопки навигации web-формы), f618f175290f73f3d737c341f5db8d7d (текущий месяц в sync до сегодня), 6955f62cdee8ab313f4f7813054f25271ea776b1 (исправление ICO payload), f1b0f1c26ff0330d760cf0124bb4c74dc4bedf27 (подключение favicon в HTML), fd22afe7c1484896793ad696dead1cb590e642ca (CSS навигации для «Сегодня»), ec787ad07d3e6b0626e555725405f8861c7241af (обработка «Сегодня» и favicon), 09b6623cf8d54cabc46e78f4c56455b8e5dc3125 (кнопка «Сегодня» в web-форме), 945a81e1dfcf6d264ff509842a847afb98e06d12 (Самочувствие 0–9), 1903b1f7ebc413e1f761a5b8dc38d35c05e653fb (retry при временной недоступности backend), 6ff4f94b46cacbd15aa30374e80f400e309586ef (hourly storage reconciliation), fdd5957ab7920840ba038b302c7f66ab55a52cd7 (hourly backend loop), 9f129bcedb0b430688e670f3f0a15a5c3310e236 (GET/POST storage sync API), 346b6ef967597ec0e9f8581844a7796e90d34874 (form sync link), 9ae483ba4bc1aa80c898ae8b68adfd14461702cd (sync link CSS), 68babede1bcc00d740815e4fff9e06a8d1019083 (Edge-visible date navigation CSS), 5e41bfd76d0d93a53b2d7ac7cfc4d889cc8c0bcd (Edge-safe date navigation markup), f744cda03e8056f6a9df7f98403f04aaf5fd4f92 (release APK filename), 9d345c08585daefe61ae9f71f4ba0eccb66ed090 (release signing property defaults), 85be81f04652c03d841b4c7e9da49c51f35b1d54 (release signing config cleanup), cc5ad10946d6f9938b0ee1ca0d87b87a63e3ac37 (external release signing secrets), 2279e1362dc91d5385f3f1815c7597cf13b8624d (v81), 04adff5573431479cc87a16771128980c24ab243 (local release signing), 92d76d55cfc95321fc482dddc8f1ae384ce1c000 (signing secrets ignored).

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


## Android UI — история синхронизаций 05.10.2026

- Добавлено локальное persistent-хранилище SyncHistory отдельно от outbox: после полного успешного цикла server /sync + ACK данные сохраняются для отображения в приложении.
- История хранит до 30 последних дат; UI будет показывать только последние 7 дней.
- В той же записи сохраняется timestamp последней полностью успешной синхронизации.
- Это не меняет очередь: SyncQueue по-прежнему очищается только после успешного ACK.
- Code commit: 072de9b.

## Android background sync — актуальное расследование 05.10.2026

- Ручной Sync Now на v81 подтверждён end-to-end: Xiaomi SPP/auth → получение activity → /login HTTP 200 → /sync HTTP 200 → ACK файлов.
- dumpsys jobscheduler подтвердил, что собственная periodic-задача существует: com.example.sleepmonitorsync, SystemJobService, интервал 60 минут, следующий запуск на момент проверки был примерно через 32 минуты.
- При этом история JobScheduler показывает для UID приложения 2x canceled и не показывает успешного завершения background job в проверенном окне.
- Причина в коде: SyncScheduler.schedule() использовал ExistingPeriodicWorkPolicy.UPDATE. Он вызывается из SleepMonitorApp.onCreate() и BootReceiver, поэтому каждый новый процесс приложения или reboot мог заменять существующую periodic-задачу и заново отсчитывать 60 минут. Это могло откладывать автоматическую синхронизацию при перезапусках процесса.
- Исправлено в v82: policy изменена на ExistingPeriodicWorkPolicy.KEEP, поэтому существующий hourly timer больше не сбрасывается при обычном старте процесса/boot. Интервал остаётся 60 минут.
- AppVersion.NUMBER повышен до 82; локальная сборка APK и реальный v82 background E2E ещё не выполнены пользователем.
- После установки v82 нужно проверить, что background SyncWorker реально запускается без открытия UI и что Logcat содержит Starting Xiaomi band background sync (v82...), затем /login → /sync → ACK.
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
- `.gitignore` дополнен исключениями для локальных SQLite-файлов, чтобы база с данными не попадала в Git.
- README уточнён для двух backend-хранилищ: актуальное значение при ручном сохранении берётся из выбранного `STORAGE_SERVICE`, а Obsidian больше не описывается как безусловно основное хранилище.
- Убраны из `main.py` формулировки, жёстко привязанные к Obsidian: UI/cache/save теперь описывают активное выбранное storage-хранилище.
- Маркер ежемесячной синхронизации теперь учитывает `STORAGE_SERVICE`, поэтому переключение `sqlite` ↔ `obsidian` не пропускает нужную сверку; добавлена совместимость с уже созданной SQLite-таблицей через добавление `source_service`.
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

## Логи httpx — убрать DEBUG 05.10.2026

- `httpx` теперь имеет уровень `WARNING`, поэтому `DEBUG`-строки вроде `load_ssl_context ...` и `HTTP Request: GET ... 200 OK` не попадают в обычный лог приложения.
- Удалён предыдущий filter, который специально переводил успешные HTTP-запросы `httpx` в `DEBUG`: теперь эти диагностические строки полностью скрыты.
- Code commit: `ed0a384`.
- Runtime-проверка после перезапуска контейнера ещё не выполнена.

## Web-сессия формы — таймаут 1 час 05.10.2026

- Cookie `session_pin` теперь получает `max_age=3600` секунд и `SameSite=Lax` при успешном входе.
- После истечения часа браузер перестаёт отправлять cookie, поэтому следующий заход на форму (`/`) уходит на `/login`.
- Если таймаут произошёл прямо во время отправки формы, `POST /save` теперь делает redirect на `/login` вместо показа JSON/401; Android `/sync` и API по-прежнему получают HTTP 401, чтобы не ломать автоматическую синхронизацию.
- Реальный runtime-тест истечения cookie пока не выполнен.
- Code commit: `e6378e1`.

## Логи Obsidian — сокращение шума 05.10.2026

- Повторяющиеся ошибки чтения Obsidian с одинаковым текстом (например, `Connection refused`) теперь логируются только один раз до восстановления связи; после успешного GET состояние ошибки сбрасывается. Это убирает пачку одинаковых строк при недоступном Obsidian, не меняя поведение ошибок: `ObsidianFetchError` по-прежнему поднимается вызывающему коду.
- Успешные HTTP-запросы `httpx` к Obsidian (`HTTP Request: GET ... 200 OK`) переведены с INFO на DEBUG, чтобы обычная ежемесячная сверка не засоряла production-log. Общий уровень приложения остаётся INFO.
- Изменения сделаны в `app/obsidian.py` и `app/main.py`; runtime-проверка новой конфигурации логирования ещё не выполнена.

## Release BAT — текущая версия сообщения 05.10.2026

- Пользователь самостоятельно переделал `android_sync_app/build-release-install.bat` в commit `1d2f663` (`apk's build version added to debug message`).
- Считать прежнее описание про отдельный commit `5d64b7e` устаревшим: актуальное состояние BAT определяется `1d2f663`.

## Правила разработки

- APK не собирать в рабочем контейнере; сборку выполняет пользователь локально.
- После изменения Android-кода повышать AppVersion.
- После каждого изменения кода обновлять этот файл.
- Не менять secure SPP без реального logcat.
- ACK выполнять только после успешного server sync.
- Документация описывает текущее поведение.
- Xiaomi auth key, APP_PIN и Obsidian API key не хранить в Git/README/Logcat.


## Проверка после текущих изменений

- Runtime/E2E новой конфигурации `STORAGE_SERVICE=sqlite` ещё не выполнен пользователем.
- Нужно проверить `/save` и `/sync` при `STORAGE_SERVICE=sqlite`, затем переключить на `obsidian` и проверить месячную сверку.
- Нужно проверить автоматическую месячную синхронизацию и ручной `POST /api/storage/sync`.
- Нужно убедиться, что SQLite-файл сохраняется после `docker compose down/up`.

## Ближайшие шаги

1. E2E v80: сервер недоступен → очередь → восстановление → повтор → ACK → очистка очереди.
2. Проверить несколько последовательных дней и pulse_avg_day/pulse_avg_sleep.
3. Обновить production HELOR до проверенного commit.
4. Проверить release signing через внешний `_secrets/sleepmon-apk`, собрать подписанный release APK и провести финальный E2E.
5. После успешного E2E считать конфигурацию готовой к эксплуатации.
