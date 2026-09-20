import { Context, InlineKeyboard } from "grammy";
import { getState, setState } from "../state/store.js";
import { ConversationStep } from "../state/machine.js";
import { sessionLog } from "../services/session-log.js";
import { config } from "../config.js";
import { logger } from "../utils/logger.js";

const cancelKeyboard = new InlineKeyboard().text("❌ Отмена", "cancel");

/**
 * Resolves the Telegram file id of the receipt image from an incoming message.
 *
 * Prefers an attached image DOCUMENT (sent "as a file" — uncompressed, full resolution) so
 * the original quality is always processed. Falls back to the largest compressed `photo`
 * when the user sent it the normal way. Returns null if the message carries no usable image.
 */
export function resolveImageFileId(ctx: Context): string | null {
  const doc = ctx.message?.document;
  if (doc && (doc.mime_type ?? "").startsWith("image/")) {
    return doc.file_id;
  }
  const photos = ctx.message?.photo;
  if (photos && photos.length > 0) {
    return photos[photos.length - 1].file_id; // largest rendition
  }
  return null;
}

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

  // Resolve the file id to use. Prefer an attached image DOCUMENT (uncompressed original)
  // over a compressed `photo`, so the highest-quality source is always processed.
  const fileId = resolveImageFileId(ctx);
  if (!fileId) {
    // A document that isn't an image while awaiting a receipt: guide the user.
    if (ctx.message?.document) {
      await ctx.reply("Пришлите изображение чека (фото или файл-картинку).");
    }
    return;
  }
  const largestPhoto = { file_id: fileId };

  // Feature flag: OCR disabled → old flow (single photo → AWAIT_SUM)
  if (!config.ocr.enabled) {
    state.photoFileId = largestPhoto.file_id;
    state.photoFileIds = [largestPhoto.file_id];
    state.step = ConversationStep.AWAIT_SUM;
    await setState(state);
    if (state.sessionId) {
      await sessionLog.updateSession(state.sessionId, {
        step: ConversationStep.AWAIT_SUM,
        photoFileIds: state.photoFileIds,
      });
    }
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

  if (state.sessionId) {
    await sessionLog.updateSession(state.sessionId, {
      step: ConversationStep.AWAIT_MORE_PAGES,
      photoFileIds: state.photoFileIds,
    });
  }

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
