# EasyShell

**Легковесная платформа управления серверами и интеллектуальной эксплуатации**

Архитектура Server-Agent | Пакетное выполнение скриптов | Логи в реальном времени | AI-операции | Уведомления через ботов

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Docs](https://img.shields.io/badge/Docs-docs.easyshell.ai-green.svg)](https://docs.easyshell.ai)

**Язык**: [English](./README.md) | [简体中文](./README.zh-CN.md) | [繁體中文](./README.zh-TW.md) | [한국어](./README.ko.md) | Русский | [日本語](./README.ja.md)

---

## Особенность: AI-управляемые плановые проверки

> **Плановая задача → Выполнение скрипта → AI-анализ → Уведомление бота** — полностью автоматизированный конвейер проверки серверов.

EasyShell может запускать **плановые задачи проверки** на ваших серверах по cron-выражениям, автоматически собирать вывод скриптов (использование дисков, состояние сервисов, логи и т.д.), отправлять результаты в **AI-модель для интеллектуального анализа** и отправлять аналитический отчёт вашей команде через **бот-каналы** — без какого-либо участия человека.

**Поддерживаемые бот-каналы** ([Руководство по настройке](https://docs.easyshell.ai/configuration/bot-channels/)):

| Бот | Статус |
|-----|--------|
| [Telegram](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ Поддерживается |
| [Discord](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ Поддерживается |
| [Slack](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ Поддерживается |
| [DingTalk (ДингТок)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ Поддерживается |
| [Feishu (ФейШу)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ Поддерживается |
| [WeCom (ВиКом)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ Поддерживается |

**Как это работает:**
1. **Настройте** плановую задачу: cron-выражение + shell-скрипт + AI-промпт
2. **Выполнение** — EasyShell отправляет скрипт на целевые агенты по расписанию
3. **Анализ** — Вывод скрипта отправляется в настроенную AI-модель (OpenAI / Gemini / GitHub Copilot / пользовательская)
4. **Уведомление** — AI-отчёт отправляется в бот-канал (Telegram, Discord, Slack и др.)

## Быстрый старт

```bash
git clone https://github.com/easyshell-ai/easyshell.git
cd easyshell
cp .env.example .env      # Отредактируйте .env при необходимости
docker compose up -d
```

Локальная сборка не требуется — готовые образы автоматически загружаются из [Docker Hub](https://hub.docker.com/u/laolupaojiao).

Откройте `http://localhost:18880` → войдите с логином `easyshell` / `easyshell@changeme`.

> **Хотите использовать GHCR?** Укажите в `.env`:
> ```
> EASYSHELL_SERVER_IMAGE=ghcr.io/easyshell-ai/easyshell/easyshell-server:latest
> EASYSHELL_WEB_IMAGE=ghcr.io/easyshell-ai/easyshell/easyshell-web:latest
> ```

> **Разработчик? Сборка из исходного кода:**
> ```bash
> docker compose -f docker-compose.build.yml up -d
> ```

## Архитектура

```
┌──────────────┐       HTTP/WS        ┌──────────────────┐
│  EasyShell   │◄─────────────────────►│   EasyShell      │
│    Agent     │  register / heartbeat │     Server       │
│  (Go 1.24)  │  script exec / logs   │ (Spring Boot 3.5)│
└──────────────┘                       └────────┬─────────┘
                                                │
                                       ┌────────┴─────────┐
                                       │   EasyShell Web   │
                                       │ (React + Ant Design)│
                                       └──────────────────┘
```

## Технологический стек

| Компонент | Технология |
|-----------|-----------|
| Server | Java 17, Spring Boot 3.5, Gradle, JPA/Hibernate, Spring AI, Spring Security |
| Agent | Go 1.24, единый бинарник, без runtime-зависимостей |
| Web | React 19, TypeScript, Vite 7, Ant Design 6 |
| База данных | MySQL 8.0 |
| Кэш | Redis 7 |

## Возможности

| Категория | Функции |
|-----------|---------|
| **Инфраструктура** | Управление хостами, мониторинг, группировка кластеров |
| **Эксплуатация** | Библиотека скриптов, пакетное выполнение, логи в реальном времени, веб-терминал |
| **AI-интеллект** | AI-чат, **плановые проверки + AI-анализ + уведомления ботов**, отчёты, утверждение операций |
| **Администрирование** | Управление пользователями, системные настройки, конфигурация AI-моделей, контроль рисков, оркестрация агентов |
| **Платформа** | Интернационализация (EN / ZH), тёмная/светлая тема, адаптивный дизайн, журнал аудита |

## Скриншоты

### Управление хостами
![Управление хостами](https://easyshell.ai/images/features/host-management.png)

### Выполнение скриптов
![Выполнение скриптов](https://easyshell.ai/images/features/script-execution.png)

### Логи в реальном времени
![Логи в реальном времени](https://easyshell.ai/images/features/realtime-logs.png)

### Веб-терминал
![Веб-терминал](https://easyshell.ai/images/features/web-terminal.png)

### AI-операции
![AI-операции](https://easyshell.ai/images/features/ai-operations.png)

### Контроль безопасности
![Контроль безопасности](https://easyshell.ai/images/features/security-controls.png)

### Интеграция ботов

Поддерживается [Telegram](https://docs.easyshell.ai/configuration/bot-channels/) · [Discord](https://docs.easyshell.ai/configuration/bot-channels/) · [Slack](https://docs.easyshell.ai/configuration/bot-channels/) · [DingTalk](https://docs.easyshell.ai/configuration/bot-channels/) · [Feishu](https://docs.easyshell.ai/configuration/bot-channels/) · [WeCom](https://docs.easyshell.ai/configuration/bot-channels/) — интерактивный чат и уведомления плановых проверок.

![Интеграция ботов](docs/images/bot-integration.png)

## Структура проекта

```
easyshell/
├── easyshell-server/           # Центральный сервер управления (Java / Spring Boot)
├── easyshell-agent/            # Клиент Agent (Go, единый бинарник)
├── easyshell-web/              # Веб-интерфейс (React + Ant Design)
├── docker-compose.yml          # Продакшен-развёртывание (готовые образы)
├── docker-compose.build.yml    # Разработка (локальная сборка из исходников)
├── Dockerfile.server           # Server + Agent многоэтапная сборка
├── Dockerfile.web              # Веб-интерфейс многоэтапная сборка
├── .github/workflows/          # CI/CD: сборка и публикация Docker-образов
└── .env.example                # Шаблон конфигурации окружения
```

## Документация

Посетите **[docs.easyshell.ai](https://docs.easyshell.ai)**:

- Руководство по установке и развёртыванию
- Начало работы
- Справочник по конфигурации
- Руководство для разработчиков

## Лицензия

Этот проект лицензирован по [лицензии MIT](./LICENSE).
