import { Context, InlineKeyboard } from "grammy";
import { getState, setState, clearState } from "../state/store.js";
import { ConversationStep } from "../state/machine.js";
import { validateSum, validateText } from "../utils/validators.js";
import { downloadFile } from "../services/telegram.js";
import { uploadPhoto } from "../services/drive.js";
import { appendReceiptRow } from "../services/sheets.js";
import { logger } from "../utils/logger.js";
import { Bot } from "grammy";

const cancelKeyboard = new InlineKeyboard().text("❌ Отмена", "cancel");

export function createTextHandler(bot: Bot) {
  return async function handleText(ctx: Context) {
    const telegramId = ctx.from?.id;
    if (!telegramId) return;

    const text = ctx.message?.text;
    if (!text) return;

    // Ignore commands
    if (text.startsWith("/")) return;

    const state = await getState(telegramId);
    if (!state) {
      await ctx.reply("Отправьте /start чтобы начать.");
      return;
    }

    switch (state.step) {
      case ConversationStep.AWAIT_PHOTO: {
        await ctx.reply("Пожалуйста, отправьте фото чека 📸");
        break;
      }

      case ConversationStep.AWAIT_SUM: {
        const sum = validateSum(text);
        if (sum === null) {
          await ctx.reply("Введите сумму числом (например: 340 или 55,45 или 1200.00)", { reply_markup: cancelKeyboard });          return;
        }
        state.sum = sum;
        state.step = ConversationStep.AWAIT_DESCRIPTION;
        await setState(state);
        await ctx.reply("Что куплено?", { reply_markup: cancelKeyboard });
        break;
      }

      case ConversationStep.AWAIT_DESCRIPTION: {
        const description = validateText(text, 500);
        if (!description) {
          await ctx.reply("Введите описание (до 500 символов)");
          return;
        }
        state.description = description;
        state.step = ConversationStep.AWAIT_STORE;
        await setState(state);
        await ctx.reply("Название магазина?", { reply_markup: cancelKeyboard });
        break;
      }

      case ConversationStep.AWAIT_STORE: {
        const storeName = validateText(text, 200);
        if (!storeName) {
          await ctx.reply("Введите название магазина (до 200 символов)");
          return;
        }
        state.storeName = storeName;
        state.step = ConversationStep.SAVING;
        await setState(state);

        // Save flow
        await saveReceipt(ctx, bot, state);
        break;
      }

      case ConversationStep.SELECT_PROJECT: {
        await ctx.reply("Выберите проект из кнопок выше, или отправьте /start заново.");
        break;
      }

      default: {
        await ctx.reply("Отправьте /start чтобы начать.");
        break;
      }
    }
  };
}

async function saveReceipt(ctx: Context, bot: Bot, state: any) {
  const telegramId = ctx.from!.id;

  try {
    await ctx.reply("⏳ Сохраняю...");

    // Download photo from Telegram
    const { buffer, mimeType } = await downloadFile(bot, state.photoFileId);

    // Upload to Drive (Shared Drive)
    let photoLink: string;
    try {
      const result = await uploadPhoto(
        state.projectDriveUrl,
        state.storeName,
        state.sum,
        buffer,
        mimeType
      );
      photoLink = result.webViewLink;
    } catch (err: any) {
      logger.error("Drive upload failed", { telegramId, error: err.message });
      // Retry once
      await new Promise(r => setTimeout(r, 2000));
      try {
        const result = await uploadPhoto(
          state.projectDriveUrl,
          state.storeName,
          state.sum,
          buffer,
          mimeType
        );
        photoLink = result.webViewLink;
      } catch (err2: any) {
        logger.error("Drive upload retry failed", { telegramId, error: err2.message });
        await ctx.reply("❌ Ошибка загрузки фото. Попробуйте ещё раз (/start)");
        return;
      }
    }

    // Write to Sheets
    const today = new Date().toISOString().split("T")[0];
    const addedBy = [ctx.from!.first_name, ctx.from!.last_name].filter(Boolean).join(" ");
    try {
      await appendReceiptRow(state.projectSheetsUrl, {
        date: today,
        sum: state.sum,
        description: state.description,
        storeName: state.storeName,
        photoLink,
        addedBy,
      });
    } catch (err: any) {
      logger.error("Sheets write failed", { telegramId, error: err.message });
      await new Promise(r => setTimeout(r, 2000));
      try {
        await appendReceiptRow(state.projectSheetsUrl, {
          date: today,
          sum: state.sum,
          description: state.description,
          storeName: state.storeName,
          photoLink,
          addedBy,
        });
      } catch (err2: any) {
        logger.error("Sheets write retry failed", { telegramId, error: err2.message });
        await ctx.reply("❌ Ошибка записи в таблицу. Фото загружено, но строка не добавлена. Попробуйте /start");
        return;
      }
    }

    // Success
    await clearState(telegramId);
    await ctx.reply(
      `✅ Записал: ${state.projectName}, ${state.sum || 0} PLN, ${state.storeName}, ${state.description}\n\nМожете отправить следующий чек или выбрать другой проект (/start)`
    );

    logger.info("Receipt saved", {
      telegramId,
      project: state.projectName,
      sum: state.sum,
      store: state.storeName,
    });
  } catch (err: any) {
    logger.error("Save receipt error", { telegramId, error: err.message });
    await ctx.reply("⚠️ Произошла ошибка. Попробуйте позже или напишите /start");
  }
}
