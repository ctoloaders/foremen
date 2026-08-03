# Деплой Google Apps Script

## Шаг 1: Открыть редактор

1. Открой любой из спредшитов (например Реестр работников):
   https://docs.google.com/spreadsheets/d/10Y_f33aXzBhCz55y-l-GeX5raYeyTZb9gGiQt6azchs/edit
2. Меню → **Расширения** → **Apps Script**

## Шаг 2: Вставить код

1. Удали всё содержимое файла `Code.gs`
2. Вставь содержимое файла `Code.gs` из этой папки
3. Убедись что `CONFIG` в начале файла содержит правильные ID

## Шаг 3: Деплой как Web App

1. Нажми **Deploy** → **New deployment**
2. Тип: **Web app**
3. Настройки:
   - Description: "Foremen Bot Webhook"
   - Execute as: **Me** (твой аккаунт)
   - Who has access: **Anyone**
4. Нажми **Deploy**
5. Скопируй URL деплоя (выглядит как `https://script.google.com/macros/s/.../exec`)

## Шаг 4: Сохранить URL

Этот URL нужно будет вставить в Битрикс24 роботы как endpoint для webhook.

**URL вебхука:** `https://script.google.com/macros/s/XXXXX/exec`

## Тестирование

Можно протестировать webhook через curl:

```bash
curl -X POST "https://script.google.com/macros/s/XXXXX/exec" \
  -H "Content-Type: application/json" \
  -d '{
    "secret": "foremen-apps-script-secret-2024",
    "action": "upsert_worker",
    "bitrix_user_id": "test_123",
    "worker_name": "Тест Тестович",
    "role": "foreman",
    "telegram_id": 123456789
  }'
```

Ожидаемый ответ: `{"status":"ok","message":"worker upserted"}`

## Обновление кода

При изменении `Code.gs`:
1. Обнови код в редакторе
2. Deploy → **Manage deployments** → выбери текущий → **Edit** (карандаш) → Version: **New version** → Deploy
