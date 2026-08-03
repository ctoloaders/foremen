/**
 * Cloud Function entry points (webhook mode).
 */

import { createBot } from "./bot.js";
import { logger } from "./utils/logger.js";
export { bitrixWebhook } from "./webhook.js";

const bot = createBot();

export async function receiptBot(req: any, res: any): Promise<void> {
  try {
    const update = req.body;
    await bot.handleUpdate(update);
    res.status(200).send("OK");
  } catch (err: any) {
    logger.error("Webhook handler error", { error: err.message });
    res.status(200).send("OK"); // Always 200 to prevent Telegram retries
  }
}
