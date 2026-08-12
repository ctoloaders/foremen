/**
 * Cloud Function entry points (webhook mode).
 */

import { createBot } from "./bot.js";
import { logger } from "./utils/logger.js";
import { handleBitrixEvent } from "./bitrix-event.js";
export { bitrixWebhook } from "./webhook.js";

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
    // Cloud Functions parses body automatically - reconstruct raw form data
    let rawBody: string;
    if (typeof req.body === "string") {
      rawBody = req.body;
    } else if (req.body && typeof req.body === "object") {
      // Form-encoded parsed by Cloud Functions into object
      rawBody = new URLSearchParams(req.body as any).toString();
      // Handle nested objects like document_id[2]
      if (req.body["document_id"] || req.body["document_id[2]"]) {
        const parts: string[] = [];
        for (const [k, v] of Object.entries(req.body)) {
          parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
        }
        rawBody = parts.join("&");
      }
    } else {
      rawBody = "";
    }
    const result = await handleBitrixEvent(rawBody);
    res.status(200).json(result);
  } catch (err: any) {
    logger.error("Bitrix event error", { error: err.message });
    res.status(500).json({ status: "error", message: err.message });
  }
}
