import { Context, InlineKeyboard } from "grammy";
import { getState, setState } from "../state/store.js";
import { ConversationStep } from "../state/machine.js";
import { validateSum, validateText } from "../utils/validators.js";
import { compareSums } from "../utils/sum-compare.js";
import { handleCategoryDetail, finalizeSave } from "./category.js";
import { sessionLog } from "../services/session-log.js";
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

      case ConversationStep.AWAIT_MORE_PAGES: {
        await ctx.reply("Пожалуйста, отправьте фото чека или нажмите кнопку");
        break;
      }

      case ConversationStep.CONFIRM_SUM: {
        await ctx.reply("Пожалуйста, используйте кнопки для подтверждения.");
        break;
      }

      case ConversationStep.AWAIT_SUM: {
        const sum = validateSum(text);
        if (sum === null) {
          await ctx.reply("Введите сумму числом (например: 340 или 55,45 или 1200.00)", { reply_markup: cancelKeyboard });
          return;
        }
        state.sum = sum;

        // OCR verification (only if ocrGrossAmount exists)
        if (state.ocrGrossAmount !== undefined && state.ocrGrossAmount !== null) {
          const { match } = compareSums(sum, state.ocrGrossAmount);
          if (!match) {
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

        // Sum matches or no OCR amount
        // Check if OCR flow (has ocrDescription + ocrStoreName)
        if (state.ocrDescription && state.ocrStoreName) {
          // OCR flow: category was already chosen at the start → save now.
          state.step = ConversationStep.SAVING;
          await setState(state);
          if (state.sessionId) {
            await sessionLog.updateSession(state.sessionId, { step: ConversationStep.SAVING, sum: state.sum });
          }
          await finalizeSave(ctx, bot, state);
        } else {
          // Manual flow — continue to description
          state.step = ConversationStep.AWAIT_DESCRIPTION;
          await setState(state);
          if (state.sessionId) {
            await sessionLog.updateSession(state.sessionId, { step: ConversationStep.AWAIT_DESCRIPTION, sum: state.sum });
          }
          await ctx.reply("Что куплено?", { reply_markup: cancelKeyboard });
        }
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
        if (state.sessionId) {
          await sessionLog.updateSession(state.sessionId, { step: ConversationStep.AWAIT_STORE, description });
        }
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
        if (state.sessionId) {
          await sessionLog.updateSession(state.sessionId, { step: ConversationStep.SAVING, storeName });
        }

        // Manual flow complete (category already chosen at start) → save.
        await finalizeSave(ctx, bot, state);
        break;
      }

      case ConversationStep.AWAIT_CATEGORY_DETAIL: {
        await handleCategoryDetail(ctx, bot, state, text);
        break;
      }

      case ConversationStep.SELECT_CATEGORY: {
        await ctx.reply("Пожалуйста, выберите категорию кнопкой выше, или /start заново.");
        break;
      }

      case ConversationStep.SELECT_PROJECT: {
        await ctx.reply("Выберите объект (проект) из кнопок выше, или отправьте /start заново.");
        break;
      }

      default: {
        await ctx.reply("Отправьте /start чтобы начать.");
        break;
      }
    }
  };
}

