import { Bot, Context, InlineKeyboard } from "grammy";
import { getState, setState } from "../state/store.js";
import { ConversationStep, ConversationState } from "../state/machine.js";
import { downloadFile } from "../services/telegram.js";
import { extractTextFromPages } from "../services/ocr.js";
import { parseReceipt } from "../services/gemini.js";
import { handleCategorySelection, finalizeSave } from "./category.js";
import { sessionLog } from "../services/session-log.js";
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

      if (state.sessionId) {
        await sessionLog.updateSession(state.sessionId, {
          step: ConversationStep.AWAIT_SUM,
          description: receiptData.description,
          storeName: receiptData.store_name,
        });
      }

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

  return async function handleOcrCallbacks(ctx: Context): Promise<void> {
    const data = ctx.callbackQuery?.data;
    const telegramId = ctx.from?.id;
    if (!data || !telegramId) return;

    await ctx.answerCallbackQuery();

    const state = await getState(telegramId);
    if (!state) return;

    // Expense category selection (cat:N)
    if (data.startsWith("cat:")) {
      await handleCategorySelection(ctx, bot, state, data);
      return;
    }

    switch (data) {
      case "ocr:more_pages": {
        // Transition back to AWAIT_PHOTO for next page
        state.step = ConversationStep.AWAIT_PHOTO;
        await setState(state);
        if (state.sessionId) await sessionLog.updateSession(state.sessionId, { step: ConversationStep.AWAIT_PHOTO });
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
        if (state.sessionId) await sessionLog.updateSession(state.sessionId, { step: ConversationStep.AWAIT_PHOTO, photoFileIds: [] });
        await ctx.editMessageText("Пришлите фото чека 📸");
        break;
      }

      case "ocr:manual": {
        // Switch to manual flow — clear ocrGrossAmount
        state.step = ConversationStep.AWAIT_SUM;
        state.ocrGrossAmount = undefined;
        await setState(state);
        if (state.sessionId) await sessionLog.updateSession(state.sessionId, { step: ConversationStep.AWAIT_SUM });
        await ctx.editMessageText("Какая сумма? (число)");
        break;
      }

      case "ocr:confirm_yes": {
        // User confirms mismatched sum — category was chosen at the start → save now.
        state.step = ConversationStep.SAVING;
        await setState(state);
        await finalizeSave(ctx, bot, state);
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
