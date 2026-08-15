# Sleep Monitor

Веб-приложение для ведения дневника самочувствия в Obsidian.

## Требования
- Docker и Docker Compose
- Сервер на Ubuntu 24.04 (или любая система с Docker)
- Настроенный Syncthing для синхронизации Obsidian-vault (вне зоны ответственности данного приложения)

## Установка и запуск

1. Скопируйте файл `.env.example` в `.env` и настройте параметры:
   - `APP_PIN` — пароль/пин-код для входа в веб-приложение
   - `OBSIDIAN_API_KEY` — API ключ от плагина Obsidian Local REST API

2. Для работы с **Google Fit** необходимо разместить два файла в корне проекта:
   - `credentials.json` — OAuth 2.0 Client ID из Google Cloud Console
   - `token.json` — сгенерируется автоматически при первой успешной авторизации скрипта (на локальном ПК).

3. Запустите docker-compose:
   ```bash
   docker compose up -d
   ```

## Настройка Obsidian (VNC)
Поскольку Obsidian работает в headless режиме, первичную настройку необходимо произвести вручную:

1. Откройте в браузере `http://<IP_СЕРВЕРА>:8080` (интерфейс VNC)
2. Пройдите первоначальную настройку Obsidian:
   - Откройте примонтированный vault (папка `/vault`)
   - Включите Community Plugins (отключите безопасный режим)
   - Установите и включите плагин `Local REST API` (coddingtonbear/obsidian-local-rest-api)
   - В настройках плагина получите `API Key`
   - Запишите этот ключ в файл `.env` (`OBSIDIAN_API_KEY`)
   - Перезапустите контейнеры: `docker compose restart app`

## Использование
После настройки перейдите на `http://<IP_СЕРВЕРА>:8000` и войдите, используя `APP_PIN`.
