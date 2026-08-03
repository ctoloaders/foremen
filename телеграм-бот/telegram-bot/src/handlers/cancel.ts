import { Context } from "grammy";
import { clearState } from "../state/store.js";

export async function handleCancel(ctx: Context) {
  const telegramId = ctx.from?.id;
  if (!telegramId) return;

  await clearState(telegramId);
  await ctx.reply("Отменено. Отправьте /start для нового чека.");
}
