/**
 * Cloud Function entry points (webhook mode).
 */

import { createBot } from "./bot.js";
import { logger } from "./utils/logger.js";
import { handleBitrixEvent } from "./bitrix-event.js";

const bot = createBot();
let botInitialized = false;

export async function receiptBot(req: any, res: any): Promise<void> {
  try {
    if (!botInitialized) {
      await bot.init();
      botInitialized = true;
    }
    const update = req.body;
    await bot.handleUpdate(update);
    res.status(200).send("OK");
  } catch (err: any) {
    logger.error("Webhook handler error", { error: err.message });
    res.status(200).send("OK"); // Always 200 to prevent Telegram retries
  }
}

export async function bitrixEvent(req: any, res: any): Promise<void> {
  try {
    // Pass the parsed body straight through. Cloud Functions already parses JSON / form bodies
    // into an object (with the deal id nested at data.FIELDS.ID), and handleBitrixEvent/
    // extractDealId handle objects, JSON strings and form-encoded strings. We deliberately do
    // NOT reconstruct the body via URLSearchParams, which flattened the nested object to
    // "data=[object Object]" and lost the id.
    const result = await handleBitrixEvent(req.body);
    res.status(200).json(result);
  } catch (err: any) {
    logger.error("Bitrix event error", { error: err.message });
    res.status(500).json({ status: "error", message: err.message });
  }
}
