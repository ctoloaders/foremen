import { Bot, Context, InlineKeyboard } from "grammy";
import { getState, setState, clearState } from "../state/store.js";
import { ConversationStep, ConversationState } from "../state/machine.js";
import { downloadFile } from "../services/telegram.js";
import { extractTextFromPages } from "../services/ocr.js";
import { parseReceipt } from "../services/gemini.js";
import { uploadPhotos, formatPhotoLinks } from "../services/drive.js";
import { appendReceiptRow } from "../services/sheets.js";
import { withRetry } from "../utils/retry.js";
import { logger } from "../utils/logger.js";

const errorKeyboard = new InlineKeyboard()
  .text("🔄 Попробовать снова", "ocr:retry")
  .text("✍️ Ввести вручную", "ocr:manual");

const cancelKeyboard = new InlineKeyboard().text("❌ Отмена", "cancel");

/**
 * Creates the OCR callback handler bound to a bot instance.
 * The bot is needed to download files from Telegram.
 */
export function createOcrCallbackHandler(bot: Bot) {
  async function processOcr(ctx: Context, state: ConversationState): Promise<void> {
    const telegramId = state.telegramId;

    // 1. Set step to PROCESSING_OCR
    state.step = ConversationStep.PROCESSING_OCR;
    await setState(state);

    // 2. Send progress message
    await ctx.editMessageText("⏳ Распознаю текст...");

    try {
      // 3. Download all photos
      const buffers: Buffer[] = [];
      for (const fileId of state.photoFileIds!) {
        const { buffer } = await downloadFile(bot, fileId);
        buffers.push(buffer);
      }

      // 4. OCR all pages
      const ocrText = await extractTextFromPages(buffers);

      // 5. If empty text: show error + retry/manual buttons
      if (!ocrText) {
        await ctx.reply(
          "❌ Не удалось распознать текст на фото. Попробуйте сделать фото чётче или введите данные вручную.",
          { reply_markup: errorKeyboard }
        );
        return;
      }

      // 6. Gemini analysis
      await ctx.reply("🤖 Анализирую чек...");

      const receiptData = await parseReceipt(ocrText);

      // 7–8. If null result: show error + retry/manual buttons
      if (!receiptData) {
        await ctx.reply(
          "❌ Не удалось структурировать данные чека. Попробуйте другое фото или введите данные вручную.",
          { reply_markup: errorKeyboard }
        );
        return;
      }

      // 9. Store OCR results in state, transition to AWAIT_SUM
      state.ocrDescription = receiptData.description;
      state.ocrStoreName = receiptData.store_name;
      state.ocrGrossAmount = receiptData.gross_amount ?? undefined;
      state.description = receiptData.description;
      state.storeName = receiptData.store_name;
      state.step = ConversationStep.AWAIT_SUM;
      await setState(state);

      // 10. Display recognized data without showing the amount
      await ctx.reply(
        `📋 Распознано:\n🛒 Куплено: ${receiptData.description}\n🏪 Магазин: ${receiptData.store_name}\n\nВведите сумму покупки (число):`,
        { reply_markup: cancelKeyboard }
      );
    } catch (error: any) {
      logger.error("OCR processing error", { telegramId, error: error.message });
      await ctx.reply(
        "❌ Произошла ошибка при обработке. Попробуйте снова или введите данные вручную.",
        { reply_markup: errorKeyboard }
      );
    }
  }

  /**
   * Saves the receipt in OCR flow: downloads photos, uploads to Drive, writes row to Sheets.
   */
  async function saveReceiptOcr(ctx: Context, state: ConversationState): Promise<void> {
    const telegramId = state.telegramId;

    try {
      await ctx.reply("⏳ Сохраняю...");

      // 1. Download all photos from Telegram
      const files: Array<{ buffer: Buffer; mimeType: string }> = [];
      for (const fileId of state.photoFileIds!) {
        const { buffer, mimeType } = await downloadFile(bot, fileId);
        files.push({ buffer, mimeType });
      }

      // 2. Upload all photos to Drive (with retry)
      let links: string[];
      try {
        const result = await withRetry(() =>
          uploadPhotos(state.projectDriveUrl!, state.storeName!, state.sum!, files)
        );
        links = result.links;
      } catch (err: any) {
        logger.error("Drive upload failed (OCR flow)", { telegramId, error: err.message });
        await ctx.reply("❌ Ошибка загрузки фото. Попробуйте ещё раз (/start)");
        return;
      }

      // 3. Write row to Sheets (with retry)
      const today = new Date().toISOString().split("T")[0];
      const addedBy = [ctx.from!.first_name, ctx.from!.last_name].filter(Boolean).join(" ");
      const photoLink = formatPhotoLinks(links);

      // Determine sum note (mismatch case: user confirmed their entry over OCR)
      let sumNote: string | undefined;
      if (state.ocrGrossAmount !== undefined && state.ocrGrossAmount !== null) {
        if (state.sum !== state.ocrGrossAmount) {
          sumNote = `⚠️ OCR: ${state.ocrGrossAmount}, введено: ${state.sum} — подтверждено сотрудником`;
        }
      }

      try {
        await withRetry(() =>
          appendReceiptRow(state.projectSheetsUrl!, {
            date: today,
            sum: state.sum!,
            description: state.description!,
            storeName: state.storeName!,
            photoLink,
            addedBy,
            sumNote,
          })
        );
      } catch (err: any) {
        logger.error("Sheets write failed (OCR flow)", { telegramId, error: err.message });
        await ctx.reply("❌ Ошибка записи в таблицу. Фото загружено, но строка не добавлена. Попробуйте /start");
        return;
      }

      // 4. Success — clear state and confirm
      await clearState(telegramId);
      await ctx.reply(
        `✅ Записал: ${state.projectName}, ${state.sum} PLN, ${state.storeName}, ${state.description}\n\nМожете отправить следующий чек или выбрать другой проект (/start)`
      );

      logger.info("Receipt saved (OCR flow)", {
        telegramId,
        project: state.projectName,
        sum: state.sum,
        store: state.storeName,
        pages: state.photoFileIds?.length,
      });
    } catch (err: any) {
      logger.error("Save receipt OCR error", { telegramId, error: err.message });
      await ctx.reply("⚠️ Произошла ошибка. Попробуйте позже или напишите /start");
    }
  }

  return async function handleOcrCallbacks(ctx: Context): Promise<void> {
    const data = ctx.callbackQuery?.data;
    const telegramId = ctx.from?.id;
    if (!data || !telegramId) return;

    await ctx.answerCallbackQuery();

    const state = await getState(telegramId);
    if (!state) return;

    switch (data) {
      case "ocr:more_pages": {
        // Transition back to AWAIT_PHOTO for next page
        state.step = ConversationStep.AWAIT_PHOTO;
        await setState(state);
        await ctx.editMessageText("Пришлите следующую страницу 📸");
        break;
      }

      case "ocr:done": {
        // Start OCR processing pipeline
        await processOcr(ctx, state);
        break;
      }

      case "ocr:retry": {
        // Reset photo collection and start over
        state.photoFileIds = [];
        state.step = ConversationStep.AWAIT_PHOTO;
        await setState(state);
        await ctx.editMessageText("Пришлите фото чека 📸");
        break;
      }

      case "ocr:manual": {
        // Switch to manual flow — clear ocrGrossAmount
        state.step = ConversationStep.AWAIT_SUM;
        state.ocrGrossAmount = undefined;
        await setState(state);
        await ctx.editMessageText("Какая сумма? (число)");
        break;
      }

      case "ocr:confirm_yes": {
        // User confirms mismatched sum — proceed to save
        state.step = ConversationStep.SAVING;
        await setState(state);
        await saveReceiptOcr(ctx, state);
        break;
      }

      case "ocr:confirm_no": {
        // User wants to re-enter sum
        state.step = ConversationStep.AWAIT_SUM;
        await setState(state);
        await ctx.editMessageText("Введите сумму покупки (число):");
        break;
      }

      default:
        break;
    }
  };
}
