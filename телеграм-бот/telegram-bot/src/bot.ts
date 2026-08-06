import { Bot } from "grammy";
import { config } from "./config.js";
import { handleStart, handleProjectSelection } from "./handlers/start.js";
import { handleMyId } from "./handlers/myid.js";
import { handleCancel } from "./handlers/cancel.js";
import { handlePhoto } from "./handlers/photo.js";
import { createTextHandler } from "./handlers/text.js";
import { logger } from "./utils/logger.js";

export function createBot(): Bot {
  const bot = new Bot(config.telegram.botToken);

  // Logger middleware
  bot.use(async (ctx, next) => {
    logger.info("Update received", {
      telegramId: ctx.from?.id,
      type: ctx.update ? Object.keys(ctx.update).filter(k => k !== "update_id")[0] : "unknown",
      text: ctx.message?.text?.slice(0, 50),
    });
    await next();
  });

  // Commands
  bot.command("start", handleStart);
  bot.command("myid", handleMyId);
  bot.command("cancel", handleCancel);

  // Callback queries (project selection)
  bot.callbackQuery(/^project:/, handleProjectSelection);

  // Photo messages
  bot.on("message:photo", handlePhoto);

  // Text messages (step-by-step flow)
  const textHandler = createTextHandler(bot);
  bot.on("message:text", textHandler);

  // Error handler
  bot.catch((err) => {
    logger.error("Bot error", { error: err.message, stack: err.stack });
    notifyAdmin(bot, `🚨 Bot error:\n${err.message}`);
  });

  return bot;
}

export async function notifyAdmin(bot: Bot, message: string) {
  const adminId = config.admin.telegramId;
  if (!adminId) return;
  try {
    await bot.api.sendMessage(adminId, message.slice(0, 4000));
  } catch {
    // silently fail — don't loop on errors
  }
}
