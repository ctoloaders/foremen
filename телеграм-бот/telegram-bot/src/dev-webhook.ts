/**
 * Local HTTP server for testing the Bitrix24 webhook endpoint.
 * Runs on port 3000.
 * Usage: bun run dev:webhook
 */

import { bitrixWebhook } from "./webhook.js";
import { logger } from "./utils/logger.js";

const server = Bun.serve({
  port: 3000,
  async fetch(req) {
    if (req.method !== "POST") {
      return new Response("Method not allowed", { status: 405 });
    }

    const body = await req.json();

    // Simulate Express-like req/res
    let responseStatus = 200;
    let responseBody: any = {};

    const fakeReq = { method: "POST", body };
    const fakeRes = {
      status(code: number) {
        responseStatus = code;
        return this;
      },
      json(data: any) {
        responseBody = data;
      },
    };

    await bitrixWebhook(fakeReq, fakeRes);

    return new Response(JSON.stringify(responseBody), {
      status: responseStatus,
      headers: { "Content-Type": "application/json" },
    });
  },
});

logger.info(`Webhook dev server running on http://localhost:${server.port}`);
