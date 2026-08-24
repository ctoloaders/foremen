import { Context, InlineKeyboard } from "grammy";
import { getState, setState } from "../state/store.js";
import { ConversationStep } from "../state/machine.js";
import { config } from "../config.js";
import { logger } from "../utils/logger.js";

const cancelKeyboard = new InlineKeyboard().text("❌ Отмена", "cancel");

export async function handlePhoto(ctx: Context) {
  const telegramId = ctx.from?.id;
  if (!telegramId) return;

  logger.info("handlePhoto called", { telegramId });

  let state;
  try {
    state = await getState(telegramId);
  } catch (err: any) {
    logger.error("handlePhoto getState error", { telegramId, error: err.message });
    return;
  }

  if (!state) {
    logger.info("handlePhoto: no state", { telegramId });
    return;
  }

  logger.info("handlePhoto state", { telegramId, step: state.step });

  // Accept photos in AWAIT_PHOTO or AWAIT_MORE_PAGES state
  if (
    state.step !== ConversationStep.AWAIT_PHOTO &&
    state.step !== ConversationStep.AWAIT_MORE_PAGES
  ) {
    return;
  }

  const photos = ctx.message?.photo;
  if (!photos || photos.length === 0) return;

  // Pick largest photo (last in array)
  const largestPhoto = photos[photos.length - 1];

  // Feature flag: OCR disabled → old flow (single photo → AWAIT_SUM)
  if (!config.ocr.enabled) {
    state.photoFileId = largestPhoto.file_id;
    state.step = ConversationStep.AWAIT_SUM;
    await setState(state);
    await ctx.reply("Какая сумма? (число)", { reply_markup: cancelKeyboard });
    logger.info("Photo received (legacy flow)", { telegramId, project: state.projectName });
    return;
  }

  // OCR flow: multi-page collection
  if (!state.photoFileIds) {
    state.photoFileIds = [];
  }

  // Enforce 10-page limit
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

  await ctx.reply(`📸 Страница ${pageNum} получена.`, { reply_markup: keyboard });
  logger.info("Photo received (OCR flow)", {
    telegramId,
    project: state.projectName,
    page: pageNum,
  });
}
