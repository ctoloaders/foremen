/**
 * Local HTTP server for testing the Bitrix outgoing webhook.
 *   POST /bitrix-event — Bitrix outgoing webhook (receives deal_id, fetches deal data itself)
 *
 * This is the single supported webhook path. The legacy JSON "{secret, action}" webhook has
 * been removed in favour of the deal-event flow.
 */

import { handleBitrixEvent } from "./bitrix-event.js";
import { logger } from "./utils/logger.js";

const server = Bun.serve({
  port: 3000,
  async fetch(req) {
    const url = new URL(req.url);

    if (req.method !== "POST") {
      return new Response("Method not allowed", { status: 405 });
    }

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

    return new Response("Not found", { status: 404 });
  },
});

logger.info(`Webhook dev server running on http://localhost:${server.port}`);
logger.info(`  POST /bitrix-event — Bitrix outgoing webhook (deal_id)`);
