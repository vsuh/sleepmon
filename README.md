# sleepmon-dbtool

Бэкап/восстановление ежедневных карточек самочувствия проекта
[vsuh/sleepmon](https://github.com/vsuh/sleepmon) в компактную SQLite-базу.
Подключается к основному проекту как git-субмодуль в `tools/dbtool` и
разворачивается отдельным сервисом `dbtool` в его `docker-compose.yml`.

## Идея

Схема frontmatter карточки (`55-sleepmon/<год>/<месяц>/<дата>.md`) со временем
меняется — поля добавляются, переименовываются, убираются. Поэтому в БД нет
отдельной колонки на каждое поле: весь YAML-frontmatter каждой карточки
хранится целиком как JSON-строка в одной колонке таблицы `cards`, а тело
заметки — в другой. Изменение состава полей не требует миграции БД и не
ломает ни бэкап, ни восстановление.

Подробности схемы БД, работа `content_hash`, мягкое удаление (`is_deleted`)
и т.д. — в комментариях `sleepmon_dbtool.py`.

## Состав

| Файл | Назначение |
|---|---|
| `sleepmon_dbtool.py` | Основной CLI (`backup` / `restore` / `list` / `verify`), не зависит от Docker |
| `backup.sh` | Обёртка для cron: инкрементальный бэкап с `--prune` |
| `restore.sh` | Обёртка для ручного восстановления |
| `Dockerfile` | Контейнер с cron, python и захардкоженными путями `/vault`, `/backups` |
| `crontab` | Расписание: ежедневно в 01:00 |
| `entrypoint.sh` | Пробрасывает ENV в cron, запускает `cron -f` на переднем плане |

## Пути внутри контейнера (захардкожены в Dockerfile)

- `/vault` — весь Obsidian vault (или хотя бы `55-sleepmon`), монтируется с хоста
- `VAULT_DIR=/vault/55-sleepmon` — каталог с карточками
- `/backups` — каталог для БД и логов, монтируется с хоста отдельным volume
- `BACKUP_DB=/backups/sleepmon.sqlite`
- `LOG_FILE=/backups/backup.log`

Снаружи настраивается только то, **что** мигрируется в эти пути (volumes в
`docker-compose.yml`), а не то, где внутри контейнера их искать — это и
имелось в виду под "захардкодить пути в Dockerfile".

## Использование как субмодуля в vsuh/sleepmon

В `docker-compose.yml` основного проекта:

```yaml
  dbtool:
    build:
      context: ./tools/dbtool
    container_name: sleepmon_dbtool
    environment:
      - TZ=Europe/Moscow
    volumes:
      - /mnt/hdd/syncthing/AI.obsdn:/vault:rw
      - ${SLEEPMON_BACKUP_DIR:-/mnt/hdd/syncthing/backups/sleepmon}:/backups:rw
    restart: unless-stopped
```

`SLEEPMON_BACKUP_DIR` задаётся один раз в `.env` основного репозитория
(см. `.env.example`) — это единственное место, где нужно менять каталог
бэкапов на хосте; путь не дублируется отдельно в документации.

Vault монтируется в режиме `rw`, чтобы `restore.sh` мог писать обратно в
карточки при аварийном восстановлении; ежедневный `backup.sh` при этом
только читает файлы.

### Ежедневный бэкап

Выполняется автоматически по cron внутри контейнера в 01:00. Ручной запуск:

```bash
docker compose exec dbtool backup.sh
docker compose exec dbtool cat /backups/backup.log
```

### Восстановление

```bash
# Сначала посмотреть, что будет сделано
docker compose exec dbtool restore.sh --dry-run

# Восстановить всё, что отличается от диска
docker compose exec dbtool restore.sh --force

# Восстановить один диапазон дат
docker compose exec dbtool restore.sh --date-from 2026-08-01 --date-to 2026-08-31

# Вернуть конкретную случайно удалённую карточку
docker compose exec dbtool restore.sh --include-deleted --path-glob "2026/08/2026-08-08.md"
```

Перед восстановлением в реальный vault рекомендуется остановить/закрыть
Obsidian (`docker compose stop obsidian`), а после — снова его запустить,
чтобы он перечитал файлы с диска: инструмент пишет напрямую в файловую
систему, в обход Local REST API (осознанно — бэкап/восстановление должны
работать и тогда, когда сам Obsidian недоступен).

### Локальный запуск без Docker

```bash
pip install -r requirements.txt
python3 sleepmon_dbtool.py backup --root /path/to/55-sleepmon --db ./sleepmon.sqlite --prune
python3 sleepmon_dbtool.py restore --db ./sleepmon.sqlite --out ./restored --dry-run
python3 sleepmon_dbtool.py list --db ./sleepmon.sqlite
python3 sleepmon_dbtool.py verify --root /path/to/55-sleepmon --db ./sleepmon.sqlite
```

Полный список опций каждой команды: `python3 sleepmon_dbtool.py <команда> --help`.

 
**Субмодули:** `tools/dbtool` подключён как git-субмодуль. Обычный `git pull`
обновляет только указатель на коммит субмодуля в родительском репозитории,
но **не** обновляет сам рабочий каталог `tools/dbtool` — без явного
`git submodule update --init --recursive` он останется пустым или
устаревшим, и `docker compose up -d --build` упадёт на сборке сервиса
`dbtool` (контекст `./tools/dbtool` пуст) либо запустит там старую версию.

# 2. Первичное развёртывание на prod

```bash  
cd /path-2-prj/services/opt 
git clone --recurse-submodules https://github.com/vsuh/sleepmon.git sleepmon  
cd sleepmon  
```  

```bash  
cd /path-2-prj/services/opt/sleepmon  
git pull 
git submodule update --init --recursive  
```   

Создать рабочую конфигурацию: 
  
```bash  
cd /path-2-prj/services/opt/sleepmon
git pull 
git submodule update --init --recursive  
docker compose up -d --build app  
```  

Сервис `dbtool` в `docker-compose.yml` читает vault напрямую с 
диска (в  обход Local REST API — так бэкап работает и тогда, когда `obsidian`/`app`  недоступны) и раз в сутки в 01:00 
запускает инкрементальный бэкап по cron внутри контейнера в `/backups/sleepmon.sqlite`. Каталог на хосте, который 
монтируется в `/backups`, задаётся один раз переменной `SLEEPMON_BACKUP_DIR` в `.env` (см. `.env.example`) — это единственное 
место, где нужно менять путь; ни в `docker-compose.yml`, ни здесь он не дублируется.

