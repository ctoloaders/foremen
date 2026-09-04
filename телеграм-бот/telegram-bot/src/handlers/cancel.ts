import { Context } from "grammy";
import { getState, clearState } from "../state/store.js";
import { sessionLog } from "../services/session-log.js";

export async function handleCancel(ctx: Context) {
  const telegramId = ctx.from?.id;
  if (!telegramId) return;

  // Finalize the session as cancelled before clearing ephemeral state.
  const state = await getState(telegramId);
  if (state?.sessionId) {
    await sessionLog.finalizeCancelled(state.sessionId);
  }

  await clearState(telegramId);
  await ctx.reply("Отменено. Отправьте /start для нового чека.");
}
