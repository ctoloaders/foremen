/**
 * Local development mode: runs bot in long-polling mode.
 * Usage: bun run dev
 */

import { createBot } from "./bot.js";
import { logger } from "./utils/logger.js";

async function main() {
  const bot = createBot();

  // Delete any existing webhook (polling mode requires no webhook)
  await bot.api.deleteWebhook();

  logger.info("Bot starting in polling mode...");
  await bot.start({
    onStart: () => logger.info("Bot is running! Send /start to the bot."),
  });
}

main().catch((err) => {
  console.error("Failed to start bot:", err);
  process.exit(1);
});
