import { Context } from "grammy";
import { randomUUID } from "crypto";
import { getWorker } from "../services/sheets.js";
import { setState } from "../state/store.js";
import { ConversationStep, emptyState } from "../state/machine.js";
import { sessionLog } from "../services/session-log.js";
import { logger } from "../utils/logger.js";
import { buildCategoryKeyboard } from "./category.js";

export async function handleStart(ctx: Context) {
  const telegramId = ctx.from?.id;
  if (!telegramId) return;

  // The worker lookup and the category keyboard are independent: start both at once (both are
  // served from the registry cache on a warm instance) and only wait for what we need.
  const workerPromise = getWorker(telegramId);
  const keyboardPromise = buildCategoryKeyboard(telegramId);
  keyboardPromise.catch(() => {}); // handled below; avoids an unhandled rejection on the denied path

  // Check worker registry
  const worker = await workerPromise;
  if (!worker) {
    await ctx.reply(
      `Доступ запрещён. Обратитесь к администратору.\nВаш Telegram ID: ${telegramId}`
    );
    return;
  }

  logger.info("Worker authenticated", { telegramId, name: worker.name, role: worker.role });

  // Build the category keyboard: built-in "Материал" first, then expense categories.
  let keyboard;
  try {
    keyboard = await keyboardPromise;
  } catch (err: any) {
    logger.error("Failed to build category keyboard", { telegramId, error: err?.message });
    await ctx.reply("❌ Не удалось загрузить категории. Попробуйте позже (/start).");
    return;
  }

  // Start a fresh session.
  const sessionId = randomUUID();

  const state = emptyState(telegramId);
  state.step = ConversationStep.SELECT_CATEGORY;
  state.sessionId = sessionId;

  // Send the menu while the state and session-log rows are persisted concurrently — the user
  // no longer waits for the bookkeeping writes before seeing the buttons.
  await Promise.all([
    ctx.reply(`Привет, ${worker.name}! (${worker.role})\nВыберите категорию:`, { reply_markup: keyboard }),
    setState(state),
    sessionLog.startSession({
      sessionId,
      telegramId,
      workerName: worker.name,
      role: worker.role,
      step: ConversationStep.SELECT_CATEGORY,
    }),
  ]);
}
