import { Context } from "grammy";

export async function handleMyId(ctx: Context) {
  const telegramId = ctx.from?.id;
  await ctx.reply(`Ваш Telegram ID: ${telegramId}`);
}
