/**
 * Local HTTP server for testing webhooks.
 * Supports:
 *   POST / — original webhook (JSON body with secret, action, etc.)
 *   POST /bitrix-event — new endpoint for Bitrix outgoing webhook (receives deal_id, fetches data itself)
 */

import { bitrixWebhook } from "./webhook.js";
import { handleBitrixEvent } from "./bitrix-event.js";
import { logger } from "./utils/logger.js";

const server = Bun.serve({
  port: 3000,
  async fetch(req) {
    const url = new URL(req.url);

    if (req.method !== "POST") {
      return new Response("Method not allowed", { status: 405 });
    }

    // Route: /bitrix-event — Bitrix outgoing webhook handler
    if (url.pathname === "/bitrix-event") {
      try {
        const body = await req.text();
        const result = await handleBitrixEvent(body);
        return new Response(JSON.stringify(result), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      } catch (e: any) {
        logger.error("Bitrix event error", { error: e.message });
        return new Response(JSON.stringify({ status: "error", message: e.message }), {
          status: 500,
          headers: { "Content-Type": "application/json" },
        });
      }
    }

    // Route: / — original webhook (JSON)
    const body = await req.json();
    let responseStatus = 200;
    let responseBody: any = {};

    const fakeReq = { method: "POST", body };
    const fakeRes = {
      status(code: number) { responseStatus = code; return this; },
      json(data: any) { responseBody = data; },
    };

    await bitrixWebhook(fakeReq, fakeRes);

    return new Response(JSON.stringify(responseBody), {
      status: responseStatus,
      headers: { "Content-Type": "application/json" },
    });
  },
});

logger.info(`Webhook dev server running on http://localhost:${server.port}`);
logger.info(`  POST /          — original webhook (JSON body)`);
logger.info(`  POST /bitrix-event — Bitrix outgoing webhook (deal_id)`);
