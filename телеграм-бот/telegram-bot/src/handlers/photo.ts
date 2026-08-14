import { Context, InlineKeyboard } from "grammy";
import { getState, setState } from "../state/store.js";
import { ConversationStep } from "../state/machine.js";
import { logger } from "../utils/logger.js";

const cancelKeyboard = new InlineKeyboard().text("❌ Отмена", "cancel");

export async function handlePhoto(ctx: Context) {
  const telegramId = ctx.from?.id;
  if (!telegramId) return;

  const state = await getState(telegramId);
  if (!state || state.step !== ConversationStep.AWAIT_PHOTO) {
    // Ignore photos not in expected state
    return;
  }

  const photos = ctx.message?.photo;
  if (!photos || photos.length === 0) return;

  // Pick largest photo (last in array)
  const largestPhoto = photos[photos.length - 1];

  state.photoFileId = largestPhoto.file_id;
  state.step = ConversationStep.AWAIT_SUM;
  await setState(state);

  await ctx.reply("Какая сумма? (число)", { reply_markup: cancelKeyboard });
  logger.info("Photo received", { telegramId, project: state.projectName });
}
