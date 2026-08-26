# Design Document: OCR-распознавание чеков (Receipt OCR Recognition)

## Overview

Расширение существующего Telegram-бота для сбора чеков фичей автоматического распознавания текста с фото через Google Cloud Vision + структурирования данных через Google Gemini. Новый flow заменяет ручной ввод описания и магазина на автоматический, сохраняя ввод суммы за пользователем с верификацией по OCR-данным. Поддерживается мультистраничное сканирование (до 10 фото на один чек).

**Ключевые архитектурные решения:**
1. `DOCUMENT_TEXT_DETECTION` для Cloud Vision (лучше для структурированных документов/чеков)
2. Gemini `gemini-2.0-flash` для быстрого и дешёвого извлечения полей
3. Хранение массива photo file_ids как JSON-строка в ячейке Sheets
4. Feature flag `OCR_ENABLED` для постепенного раскатывания
5. Fallback на ручной ввод при любых ошибках OCR/Gemini

## Architecture

### Обновлённая системная диаграмма (OCR-расширение)

```
┌──────────────────────────────────────────────────────────────────┐
│                        TELEGRAM                                   │
│                                                                   │
│   [Работник] ── фото (1..10 стр.) ────► [Telegram Bot API]      │
│       ▲                                         │                 │
│       └──── ответы + inline buttons ◄───────────┼─────┐          │
│                                                 ▼     │          │
├─────────────────────────────────────────────────────────┤          │
│                  GOOGLE CLOUD                            │          │
│                                                         │          │
│   ┌────────────────────────────────────────────────┐   │          │
│   │  Cloud Function: receipt-bot                    │   │          │
│   │                                                 │───┘          │
│   │  handlers/                                      │              │
│   │    photo.ts    ← multi-page collection          │              │
│   │    text.ts     ← sum input + verification       │              │
│   │    callback.ts ← inline button handlers         │              │
│   │                                                 │              │
│   │  services/                                      │              │
│   │    ocr.ts      ← Cloud Vision integration  ────┼──► Cloud Vision API
│   │    gemini.ts   ← Gemini integration        ────┼──► Gemini API
│   │    drive.ts    ← multi-photo upload             │              │
│   │    sheets.ts   ← receipt row write              │              │
│   │                                                 │              │
│   │  state/                                         │              │
│   │    machine.ts  ← extended state machine         │              │
│   │    store.ts    ← extended persistence           │              │
│   └─────────────────────────────────────────────────┘              │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

### Новый flow (state machine)

```
[SELECT_PROJECT] ──► [AWAIT_PHOTO] ──фото──► [AWAIT_MORE_PAGES]
                                                    │
                         ┌──────────────────────────┤
                         │                          │
                    "Ещё страница"             "✅ Готово"
                         │                          │
                         ▼                          ▼
                  [AWAIT_PHOTO] ◄─        [PROCESSING_OCR]
                  (ожид. след.)                     │
                                          ┌─────────┼──────────┐
                                          │                    │
                                       success              failure
                                          │                    │
                                          ▼                    ▼
                                    [AWAIT_SUM]        "Ввести вручную"
                                          │              → manual flow
                                          │
                                     ввод суммы
                                          │
                              ┌────────────┼────────────┐
                              │                         │
                         совпадает              не совпадает
                         (±0.01)                        │
                              │                         ▼
                              │                  [CONFIRM_SUM]
                              │                    │        │
                              │              "Да"  │        │ "Заново"
                              │                    │        │
                              ▼                    ▼        ▼
                          [SAVING]            [SAVING]  [AWAIT_SUM]
```

### Стек технологий (дополнения)

| Компонент | Технология | Обоснование |
|-----------|-----------|-------------|
| OCR | Google Cloud Vision API (DOCUMENT_TEXT_DETECTION) | Лучше для структурированных документов, поддерживает русский/иврит/польский |
| Структурирование | Google Gemini (gemini-2.0-flash) | Быстрый, дешёвый, хорошо работает с JSON extraction |
| Gemini SDK | `@google/generative-ai` (npm) | Официальный SDK, простой API |

## Components and Interfaces

### Component 1: OCR Service (`services/ocr.ts`)

**Responsibility:** Извлечение текста из изображений через Google Cloud Vision API.

```typescript
interface OcrResult {
  text: string;        // Извлечённый текст (может быть пустым)
  confidence: number;  // 0-1, средняя уверенность
}

interface OcrService {
  /**
   * Извлекает текст из одного изображения.
   * Использует DOCUMENT_TEXT_DETECTION для лучшей работы с чеками.
   * @throws OcrError при сетевых ошибках (после 1 retry)
   */
  extractText(imageBuffer: Buffer): Promise<OcrResult>;

  /**
   * Извлекает и конкатенирует текст из нескольких изображений.
   * Разделяет страницы маркером "\n--- Page N ---\n".
   * Если все страницы пустые — возвращает пустую строку.
   */
  extractTextFromPages(imageBuffers: Buffer[]): Promise<string>;
}
```

**Реализация:**

```typescript
import vision from "@google-cloud/vision";

const PAGE_SEPARATOR = "\n--- Page {N} ---\n";

export async function extractText(imageBuffer: Buffer): Promise<OcrResult> {
  const client = new vision.ImageAnnotatorClient({
    credentials: config.google.serviceAccountKey,
  });

  const [result] = await client.documentTextDetection({
    image: { content: imageBuffer.toString("base64") },
  });

  const fullText = result.fullTextAnnotation?.text || "";
  const confidence = result.fullTextAnnotation?.pages?.[0]?.confidence || 0;

  return { text: fullText, confidence };
}

export async function extractTextFromPages(imageBuffers: Buffer[]): Promise<string> {
  const results: string[] = [];

  for (let i = 0; i < imageBuffers.length; i++) {
    const { text } = await withRetry(() => extractText(imageBuffers[i]));
    results.push(text);
  }

  // If all pages empty, return empty
  if (results.every(t => t.trim() === "")) return "";

  return results
    .map((text, i) => `--- Page ${i + 1} ---\n${text}`)
    .join("\n");
}
```

**Решение по TEXT_DETECTION vs DOCUMENT_TEXT_DETECTION:**
- `DOCUMENT_TEXT_DETECTION` выбран потому что:
  - Лучше распознаёт структурированные документы (таблицы, чеки)
  - Сохраняет порядок строк и блоков
  - Поддерживает multi-language detection автоматически
  - Стоимость одинаковая ($1.50 / 1000 запросов)

### Component 2: Gemini Service (`services/gemini.ts`)

**Responsibility:** Структурирование raw OCR-текста в поля чека.

```typescript
interface GeminiReceiptData {
  description: string;     // Краткое описание покупок
  store_name: string;      // Название магазина/продавца
  gross_amount: number | null; // Сумма БРУТТО (null если не найдена)
}

interface GeminiService {
  /**
   * Анализирует OCR-текст и извлекает структурированные данные.
   * @throws GeminiError при сетевых ошибках (после 1 retry)
   * @returns null если не удалось извлечь обязательные поля
   */
  parseReceipt(ocrText: string): Promise<GeminiReceiptData | null>;
}
```

**Prompt-структура:**

```typescript
const RECEIPT_PARSE_PROMPT = `You are a receipt/invoice data extraction assistant.
Analyze the following OCR text from a receipt or invoice and extract:

1. "description" — brief summary of purchased items (2-5 words in the language of the receipt, e.g. "Плитка, клей, затирка" or "Farba, pędzle, folia")
2. "store_name" — name of the shop/company/seller
3. "gross_amount" — total BRUTTO amount (the final amount to pay, as a number). If you cannot find a total, return null.

Important:
- The receipt may be in Russian, Polish, Hebrew, or English
- Look for keywords like "ИТОГО", "RAZEM", "TOTAL", "סה״כ" for the total amount
- For store name, look at the header/top of the receipt
- For description, summarize the main item categories (not individual items)
- Return ONLY a valid JSON object, no markdown, no explanation

Return format:
{"description": "...", "store_name": "...", "gross_amount": 123.45}

OCR Text:
`;

export async function parseReceipt(ocrText: string): Promise<GeminiReceiptData | null> {
  const model = genAI.getGenerativeModel({ model: config.gemini.model });

  const result = await model.generateContent(RECEIPT_PARSE_PROMPT + ocrText);
  const responseText = result.response.text();

  // Parse JSON from response (handle potential markdown wrapping)
  const jsonMatch = responseText.match(/\{[\s\S]*\}/);
  if (!jsonMatch) return null;

  const parsed = JSON.parse(jsonMatch[0]);

  // Validate required fields
  if (!parsed.description || !parsed.store_name) return null;
  if (typeof parsed.description !== "string" || typeof parsed.store_name !== "string") return null;

  return {
    description: parsed.description.trim(),
    store_name: parsed.store_name.trim(),
    gross_amount: typeof parsed.gross_amount === "number" ? parsed.gross_amount : null,
  };
}
```

### Component 3: Updated Photo Handler (`handlers/photo.ts`)

**Responsibility:** Обработка входящих фото — мультистраничный сбор.

```typescript
export async function handlePhoto(ctx: Context): Promise<void> {
  const telegramId = ctx.from?.id;
  if (!telegramId) return;

  const state = await getState(telegramId);
  if (!state) return;

  // Accept photos in AWAIT_PHOTO state (first photo or subsequent pages)
  if (state.step !== ConversationStep.AWAIT_PHOTO) return;

  const photos = ctx.message?.photo;
  if (!photos || photos.length === 0) return;

  const largestPhoto = photos[photos.length - 1];

  // Initialize or append to photoFileIds array
  if (!state.photoFileIds) {
    state.photoFileIds = [];
  }

  // Check max pages
  if (state.photoFileIds.length >= 10) {
    await ctx.reply("Максимум 10 страниц. Нажмите ✅ Готово для обработки.");
    return;
  }

  state.photoFileIds.push(largestPhoto.file_id);
  state.step = ConversationStep.AWAIT_MORE_PAGES;
  await setState(state);

  const pageNum = state.photoFileIds.length;
  const keyboard = new InlineKeyboard()
    .text("📄 Ещё 1 страница", "ocr:more_pages")
    .text("✅ Готово", "ocr:done");

  await ctx.reply(
    `📸 Страница ${pageNum} получена.`,
    { reply_markup: keyboard }
  );
}
```

### Component 4: Callback Query Handler (`handlers/callback.ts`)

**Responsibility:** Обработка inline-кнопок для OCR flow.

```typescript
export async function handleOcrCallbacks(ctx: Context): Promise<void> {
  const data = ctx.callbackQuery?.data;
  const telegramId = ctx.from?.id;
  if (!data || !telegramId) return;

  await ctx.answerCallbackQuery();
  const state = await getState(telegramId);
  if (!state) return;

  switch (data) {
    case "ocr:more_pages":
      // Transition back to AWAIT_PHOTO for next page
      state.step = ConversationStep.AWAIT_PHOTO;
      await setState(state);
      await ctx.editMessageText("Пришлите следующую страницу 📸");
      break;

    case "ocr:done":
      // Start OCR processing
      await processOcr(ctx, state);
      break;

    case "ocr:retry":
      // Reset to photo collection
      state.photoFileIds = [];
      state.step = ConversationStep.AWAIT_PHOTO;
      await setState(state);
      await ctx.editMessageText("Пришлите фото чека 📸");
      break;

    case "ocr:manual":
      // Switch to manual flow
      state.step = ConversationStep.AWAIT_SUM;
      state.ocrGrossAmount = undefined; // No OCR verification in manual mode
      await setState(state);
      await ctx.editMessageText("Какая сумма? (число)");
      break;

    case "ocr:confirm_yes":
      // User confirms mismatched sum — proceed to save
      state.step = ConversationStep.SAVING;
      await setState(state);
      await saveReceiptOcr(ctx, state);
      break;

    case "ocr:confirm_no":
      // User wants to re-enter sum
      state.step = ConversationStep.AWAIT_SUM;
      await setState(state);
      await ctx.editMessageText("Введите сумму покупки (число):");
      break;
  }
}
```

### Component 5: OCR Processing Pipeline (`handlers/callback.ts` — processOcr)

**Responsibility:** Оркестрация OCR + Gemini + отображение результата.

```typescript
async function processOcr(ctx: Context, state: ConversationState): Promise<void> {
  state.step = ConversationStep.PROCESSING_OCR;
  await setState(state);

  await ctx.editMessageText("⏳ Распознаю текст...");

  // 1. Download all photos
  const buffers: Buffer[] = [];
  for (const fileId of state.photoFileIds!) {
    const { buffer } = await downloadFile(bot, fileId);
    buffers.push(buffer);
  }

  // 2. OCR all pages
  const ocrText = await extractTextFromPages(buffers);

  if (!ocrText) {
    // All pages empty
    const keyboard = new InlineKeyboard()
      .text("🔄 Попробовать снова", "ocr:retry")
      .text("✍️ Ввести вручную", "ocr:manual");
    await ctx.reply(
      "❌ Не удалось распознать текст на фото. Попробуйте сделать фото чётче или введите данные вручную.",
      { reply_markup: keyboard }
    );
    return;
  }

  // 3. Gemini extraction
  await ctx.reply("🤖 Анализирую чек...");

  const receiptData = await parseReceipt(ocrText);

  if (!receiptData) {
    const keyboard = new InlineKeyboard()
      .text("🔄 Попробовать снова", "ocr:retry")
      .text("✍️ Ввести вручную", "ocr:manual");
    await ctx.reply(
      "❌ Не удалось структурировать данные чека. Попробуйте другое фото или введите данные вручную.",
      { reply_markup: keyboard }
    );
    return;
  }

  // 4. Store OCR results in state
  state.ocrDescription = receiptData.description;
  state.ocrStoreName = receiptData.store_name;
  state.ocrGrossAmount = receiptData.gross_amount ?? undefined;
  state.description = receiptData.description;
  state.storeName = receiptData.store_name;
  state.step = ConversationStep.AWAIT_SUM;
  await setState(state);

  // 5. Display results (without amount)
  const cancelKeyboard = new InlineKeyboard().text("❌ Отмена", "cancel");
  await ctx.reply(
    `📋 Распознано:\n🛒 Куплено: ${receiptData.description}\n🏪 Магазин: ${receiptData.store_name}\n\nВведите сумму покупки (число):`,
    { reply_markup: cancelKeyboard }
  );
}
```

### Component 6: Updated Text Handler — Sum Verification

**Responsibility:** Обработка ввода суммы с верификацией против OCR.

```typescript
// In handleText, AWAIT_SUM case (updated):
case ConversationStep.AWAIT_SUM: {
  const sum = validateSum(text);
  if (sum === null) {
    await ctx.reply("Введите сумму числом (например, 340 или 250.50)", { reply_markup: cancelKeyboard });
    return;
  }

  state.sum = sum;

  // OCR verification (only if ocrGrossAmount exists)
  if (state.ocrGrossAmount !== undefined && state.ocrGrossAmount !== null) {
    const diff = Math.abs(sum - state.ocrGrossAmount);
    if (diff > 0.01) {
      // Mismatch — ask for confirmation
      state.step = ConversationStep.CONFIRM_SUM;
      await setState(state);

      const keyboard = new InlineKeyboard()
        .text("✅ Да, сохранить", "ocr:confirm_yes")
        .text("✏️ Ввести заново", "ocr:confirm_no");

      await ctx.reply(
        `⚠️ Распознанная сумма: ${state.ocrGrossAmount}\nВы ввели: ${sum}\n\nВы уверены?`,
        { reply_markup: keyboard }
      );
      return;
    }
  }

  // Sum matches or no OCR amount — proceed to saving
  // In OCR flow: description and store already filled
  if (state.ocrDescription && state.ocrStoreName) {
    state.step = ConversationStep.SAVING;
    await setState(state);
    await saveReceiptOcr(ctx, state);
  } else {
    // Manual flow — continue to description
    state.step = ConversationStep.AWAIT_DESCRIPTION;
    await setState(state);
    await ctx.reply("Что куплено?", { reply_markup: cancelKeyboard });
  }
  break;
}
```

### Component 7: Updated State Machine (`state/machine.ts`)

**Responsibility:** Расширение enum и interface для OCR flow.

```typescript
export enum ConversationStep {
  IDLE = "idle",
  SELECT_PROJECT = "select_project",
  AWAIT_PHOTO = "await_photo",
  AWAIT_MORE_PAGES = "await_more_pages",   // NEW: waiting for more pages or "done"
  PROCESSING_OCR = "processing_ocr",       // NEW: OCR+Gemini in progress
  AWAIT_SUM = "await_sum",
  CONFIRM_SUM = "confirm_sum",             // NEW: sum mismatch confirmation
  AWAIT_DESCRIPTION = "await_desc",
  AWAIT_STORE = "await_store",
  SAVING = "saving",
}

export interface ConversationState {
  telegramId: number;
  step: ConversationStep;
  projectName?: string;
  projectDriveUrl?: string;
  projectSheetsUrl?: string;
  // Multi-page support (replaces single photoFileId)
  photoFileId?: string;          // DEPRECATED: kept for backward compat
  photoFileIds?: string[];       // NEW: array of photo file_ids
  sum?: number;
  description?: string;
  storeName?: string;
  // OCR results
  ocrDescription?: string;       // NEW: description from Gemini
  ocrStoreName?: string;         // NEW: store name from Gemini
  ocrGrossAmount?: number;       // NEW: gross amount from Gemini (verification only)
  updatedAt: string;
}
```

### Component 8: Updated State Store (`state/store.ts`)

**Responsibility:** Сериализация/десериализация расширенного состояния в Sheets.

Расширение хранилища для новых полей. `photoFileIds` хранится как JSON-строка в одной ячейке.

```typescript
// Extended columns in bot_state sheet:
// A: telegram_id
// B: step
// C: project_name
// D: project_drive_url
// E: project_sheets_url
// F: photo_file_ids (JSON array string, e.g. '["id1","id2"]')
// G: sum
// H: description
// I: store_name
// J: updated_at
// K: ocr_description
// L: ocr_store_name
// M: ocr_gross_amount

function serializePhotoIds(ids?: string[]): string {
  if (!ids || ids.length === 0) return "";
  return JSON.stringify(ids);
}

function deserializePhotoIds(raw: string): string[] | undefined {
  if (!raw) return undefined;
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) return parsed;
  } catch {}
  // Backward compat: single file_id (old format)
  return [raw];
}
```

### Component 9: Updated Drive Service — Multi-page Upload

**Responsibility:** Загрузка нескольких фото с правильным именованием.

```typescript
export async function uploadPhotos(
  driveUrl: string,
  storeName: string,
  sum: number,
  files: Array<{ buffer: Buffer; mimeType: string }>,
): Promise<{ links: string[] }> {
  const links: string[] = [];
  const isMultiPage = files.length > 1;

  for (let i = 0; i < files.length; i++) {
    const fileName = generateFileName(storeName, sum, files[i].mimeType, isMultiPage ? i + 1 : undefined);
    const result = await uploadPhoto(driveUrl, fileName, files[i].buffer, files[i].mimeType);
    links.push(result.webViewLink);
  }

  return { links };
}

function generateFileName(
  storeName: string,
  sum: number,
  mimeType: string,
  pageNum?: number
): string {
  const now = new Date();
  const date = now.toISOString().split("T")[0];
  const time = `${String(now.getHours()).padStart(2, "0")}-${String(now.getMinutes()).padStart(2, "0")}`;
  const ext = mimeType === "image/png" ? "png" : "jpg";
  const store = transliterate(storeName);

  if (pageNum !== undefined) {
    return `${date}_${time}_${store}_${sum}_page${pageNum}.${ext}`;
  }
  return `${date}_${time}_${store}_${sum}.${ext}`;
}
```

### Component 10: Updated Config (`config.ts`)

**Responsibility:** Добавление OCR/Gemini конфигурации с feature flag.

```typescript
export const config = {
  // ... existing config ...
  ocr: {
    enabled: (process.env.OCR_ENABLED || "true") === "true",
    projectId: process.env.GOOGLE_CLOUD_PROJECT_ID || "",
  },
  gemini: {
    apiKey: process.env.GEMINI_API_KEY || "",
    model: process.env.GEMINI_MODEL || "gemini-2.0-flash",
  },
} as const;

// Fail fast: if OCR enabled, require API keys
if (config.ocr.enabled) {
  if (!config.gemini.apiKey) {
    throw new Error("GEMINI_API_KEY required when OCR_ENABLED=true");
  }
}
```

### Component 11: Feature Flag Logic

**Responsibility:** Маршрутизация между OCR и manual flow.

```typescript
// In photo handler — after first photo:
if (!config.ocr.enabled) {
  // Old flow: single photo → ask sum immediately
  state.photoFileId = largestPhoto.file_id;
  state.step = ConversationStep.AWAIT_SUM;
  await setState(state);
  await ctx.reply("Какая сумма? (число)", { reply_markup: cancelKeyboard });
  return;
}

// New OCR flow: collect pages
state.photoFileIds = [largestPhoto.file_id];
state.step = ConversationStep.AWAIT_MORE_PAGES;
// ... show inline buttons
```

## Data Models

### Обновлённый лист bot_state

| Column | Field | Type | Description |
|--------|-------|------|-------------|
| A | telegram_id | integer | Ключ |
| B | step | enum | Текущий шаг (включая новые: await_more_pages, processing_ocr, confirm_sum) |
| C | project_name | string | Выбранный проект |
| D | project_drive_url | URL | Drive папка проекта |
| E | project_sheets_url | URL | Таблица-смета проекта |
| F | photo_file_ids | JSON string | Массив file_ids: `["id1","id2"]` (или одиночный id для backward compat) |
| G | sum | number | Введённая сумма |
| H | description | string | Описание (OCR или ручное) |
| I | store_name | string | Магазин (OCR или ручной) |
| J | updated_at | ISO string | Последнее обновление |
| K | ocr_description | string | Описание от Gemini (может отличаться от H если ручной ввод) |
| L | ocr_store_name | string | Магазин от Gemini |
| M | ocr_gross_amount | number | Сумма от Gemini (для верификации) |

### Gemini Response Schema

```typescript
interface GeminiReceiptResponse {
  description: string;      // "Плитка 60x60, клей, крестики"
  store_name: string;       // "Leroy Merlin"
  gross_amount: number | null; // 1250.50 или null
}
```

### Обновлённая структура файлов (новые файлы)

```
/telegram-bot/src/
├── services/
│   ├── ocr.ts           # NEW: Cloud Vision API integration
│   └── gemini.ts        # NEW: Gemini API integration
├── handlers/
│   ├── callback.ts      # NEW: inline button handler for OCR flow
│   ├── photo.ts         # UPDATED: multi-page collection
│   └── text.ts          # UPDATED: sum verification logic
├── state/
│   ├── machine.ts       # UPDATED: new steps + extended interface
│   └── store.ts         # UPDATED: serialize/deserialize new fields
└── utils/
    └── retry.ts         # NEW: generic retry helper
```

## Error Handling

### OCR/Gemini Errors

| Ситуация | Действие |
|----------|----------|
| Cloud Vision: пустой текст на всех страницах | "❌ Не удалось распознать текст" + кнопки "Снова" / "Вручную" |
| Cloud Vision: сетевая ошибка | Retry 1x (2s delay) → fallback message |
| Cloud Vision: quota exceeded | Retry 1x → fallback message + log alert |
| Gemini: invalid JSON response | Retry parse → "❌ Не удалось структурировать" + кнопки |
| Gemini: missing required fields | "❌ Не удалось структурировать" + кнопки |
| Gemini: API error (429, 500) | Retry 1x (2s delay) → fallback to manual |
| Sum mismatch | "⚠️ Распознанная сумма: X, Вы ввели: Y — Вы уверены?" |

### Retry Helper

```typescript
// utils/retry.ts
export async function withRetry<T>(
  fn: () => Promise<T>,
  retries: number = 1,
  delayMs: number = 2000
): Promise<T> {
  try {
    return await fn();
  } catch (error) {
    if (retries <= 0) throw error;
    await new Promise(r => setTimeout(r, delayMs));
    return withRetry(fn, retries - 1, delayMs);
  }
}
```

### Backward Compatibility

- Старые записи в bot_state без колонок K-M продолжают работать (поля optional)
- `photoFileId` (single) читается если `photoFileIds` (array) пуст — миграция не нужна
- `OCR_ENABLED=false` полностью отключает новый flow, бот работает как раньше

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Photo collection appends to list

*For any* valid photo file_id and any existing state in AWAIT_PHOTO with N photos collected (where N < 10), adding a photo SHALL result in the photoFileIds array having exactly N+1 elements, with the new file_id at position N.

**Validates: Requirements 1.2, 1.4**

### Property 2: Maximum page limit enforcement

*For any* conversation state with photoFileIds of length N, the system SHALL accept a new photo if and only if N < 10. When N >= 10, the photo SHALL be rejected and the list SHALL remain unchanged.

**Validates: Requirements 1.5, 1.6**

### Property 3: Non-photo messages do not mutate state during page collection

*For any* text message sent while the conversation is in AWAIT_MORE_PAGES state, the photoFileIds array and step SHALL remain unchanged (the system only responds with a prompt to send a photo).

**Validates: Requirements 1.8**

### Property 4: OCR text concatenation preserves page order

*For any* array of N page texts [t1, t2, ..., tN], the concatenated output SHALL contain t1 before t2, t2 before t3, etc., and each page's text SHALL appear exactly once in the output.

**Validates: Requirements 2.3**

### Property 5: Gemini response parsing extracts valid fields

*For any* valid JSON string containing `description` (non-empty string) and `store_name` (non-empty string) fields, the parseReceipt function SHALL return a GeminiReceiptData object with those fields. For any JSON missing either required field, it SHALL return null.

**Validates: Requirements 3.3, 3.4**

### Property 6: Display message contains OCR fields but not gross_amount

*For any* GeminiReceiptData with description D, store_name S, and gross_amount G (where G is not null), the user-facing message SHALL contain D and S, and SHALL NOT contain the string representation of G.

**Validates: Requirements 4.1, 4.2**

### Property 7: Sum comparison determines match within tolerance

*For any* two amounts A (user-entered) and B (OCR-recognized), the comparison function SHALL classify them as matching if and only if |A - B| <= 0.01. When B is null, the result SHALL always be "match" (proceed without verification).

**Validates: Requirements 5.1, 5.2, 5.3, 5.4**

### Property 8: Saved receipt always uses user-entered sum

*For any* completed receipt (both OCR and manual flows), the sum written to the spreadsheet SHALL equal the user-entered sum value, regardless of the OCR gross_amount value.

**Validates: Requirements 5.7, 9.1**

### Property 9: Feature flag disables OCR flow entirely

*For any* photo sent when OCR_ENABLED is "false", the system SHALL transition directly from AWAIT_PHOTO to AWAIT_SUM (old manual flow) without creating a photoFileIds array or calling OCR/Gemini services.

**Validates: Requirements 7.4**

### Property 10: photoFileIds JSON serialization round-trip

*For any* array of strings (photo file_ids), serializing to JSON and deserializing back SHALL produce an identical array (same length, same elements, same order).

**Validates: Requirements 8.3**

### Property 11: Multi-page file naming includes sequential page numbers

*For any* store name, sum, and page count N > 1, the generated filenames SHALL follow the pattern `YYYY-MM-DD_HH-MM_<store>_<sum>_page<i>.<ext>` for each i in [1..N]. For N = 1, the filename SHALL NOT contain a page number suffix.

**Validates: Requirements 9.3, 9.4**

### Property 12: Multi-page photo links concatenation

*For any* array of N photo links [L1, L2, ..., LN], the formatted cell value for the spreadsheet SHALL contain all N links. For N = 1, it SHALL contain exactly L1.

**Validates: Requirements 9.6**
