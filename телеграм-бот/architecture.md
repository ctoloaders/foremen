# Архитектура Telegram-бота для сбора счетов

## Минимальный стек

- **Код:** Python (aiogram/python-telegram-bot) или Node.js (grammY/Telegraf)
- **Хостинг:** Google Cloud Functions (webhook mode) — 0 инфраструктуры
- **Хранение состояния:** Firestore (free tier) или отдельный лист Google Sheets
- **Внешние API:** Google Drive API, Google Sheets API
- **Авторизация:** Google Service Account (JSON-ключ)

## Диаграмма

```mermaid
flowchart TD
    subgraph Telegram
        A[Прораб в Telegram]
    end

    subgraph "Google Cloud (serverless)"
        B[Cloud Function<br/>— код бота —<br/>webhook endpoint]
        C[(Firestore<br/>состояние диалога)]
    end

    subgraph "Google Workspace"
        D[Google Sheets<br/>Реестр проектов<br/><i>прораб → проекты</i>]
        E[Google Sheets<br/>Смета проекта<br/><i>дата, сумма, описание...</i>]
        F[Google Drive<br/>Папка проекта<br/><i>фото чеков</i>]
    end

    subgraph "Битрикс24"
        G[Битрикс24<br/>Проекты + автоматизация]
    end

    A -->|"фото + текст"| B
    B -->|"ответы, кнопки"| A
    B -->|"read: список проектов прораба"| D
    B -->|"write: новая строка"| E
    B -->|"upload: фото чека"| F
    B <-->|"read/write состояние"| C
    G -->|"автоматизация: заполняет реестр"| D
```

## Поток данных (сценарий)

```mermaid
sequenceDiagram
    participant П as Прораб
    participant Б as Бот (Cloud Function)
    participant FS as Firestore
    participant Р as Реестр (Sheets)
    participant С as Смета (Sheets)
    participant Д as Drive

    П->>Б: /start
    Б->>Р: Получить проекты по Telegram ID
    Р-->>Б: Список проектов
    Б->>П: Кнопки с проектами

    П->>Б: Выбирает проект
    Б->>FS: Сохранить состояние (выбран проект)
    Б->>П: "Пришлите фото чека"

    П->>Б: Фото
    Б->>FS: Сохранить file_id
    Б->>П: "Какая сумма?"

    П->>Б: "340"
    Б->>FS: Сохранить сумму
    Б->>П: "Что куплено?"

    П->>Б: "Саморезы 6мм"
    Б->>FS: Сохранить описание
    Б->>П: "Название магазина?"

    П->>Б: "Леруа Мерлен"
    Б->>Д: Загрузить фото в папку проекта
    Д-->>Б: Ссылка на фото
    Б->>С: Добавить строку (дата, сумма, описание, магазин, ссылка)
    Б->>FS: Очистить состояние
    Б->>П: "✅ Записал: [проект], 340₪, Леруа Мерлен, Саморезы 6мм"
```

## Что нужно сделать

| # | Задача | Сложность |
|---|--------|-----------|
| 1 | Создать Google Service Account + расшарить таблицы/папки | Настройка |
| 2 | Создать Telegram бота через @BotFather | 2 мин |
| 3 | Написать код бота (webhook handler) | ~200-300 строк |
| 4 | Задеплоить в Cloud Functions | 1 команда |
| 5 | Настроить webhook URL в Telegram | 1 API-вызов |

## Альтернатива: ещё проще (без Firestore)

Если хочется совсем без доп. сервисов — состояние диалога можно хранить
в отдельном листе того же Google Sheets (лист "bot_state").
Это чуть медленнее, но убирает зависимость от Firestore.

В этом случае весь бот зависит только от:
- ✅ Google Cloud Function (бесплатно)
- ✅ Google Sheets (бесплатно)
- ✅ Google Drive (бесплатно, 15GB)
- ✅ Telegram Bot API (бесплатно)

**Итого: $0/мес при нормальной нагрузке (до ~100 чеков/день).**
