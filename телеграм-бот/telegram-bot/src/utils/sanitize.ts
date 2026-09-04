import { config } from "../config.js";

const MAX_TRACE_LENGTH = 4000;
const REDACTED = "***REDACTED***";

/**
 * Collects the concrete secret values that must never appear in a persisted
 * error trace. Sourced from config so redaction stays in sync with runtime.
 */
function collectSecretValues(): string[] {
  const secrets: Array<string | undefined> = [
    config.telegram.botToken,
    config.telegram.webhookSecret,
    config.appsScript.webhookSecret,
    config.gemini.apiKey,
  ];

  // Service account private key (multi-line PEM) — redact each non-trivial line.
  const sa = config.google.serviceAccountKey as Record<string, unknown> | undefined;
  const privateKey = sa && typeof sa.private_key === "string" ? sa.private_key : undefined;
  if (privateKey) {
    secrets.push(privateKey);
    for (const line of privateKey.split("\n")) {
      const trimmed = line.trim();
      if (trimmed && !trimmed.startsWith("-----") && trimmed.length > 16) {
        secrets.push(trimmed);
      }
    }
  }

  return secrets.filter((s): s is string => typeof s === "string" && s.length > 0);
}

/**
 * Redacts credential-bearing query strings from any URL-like substrings.
 * Replaces the value of common secret-ish query params with ***REDACTED***.
 */
function redactUrlQuerySecrets(text: string): string {
  return text.replace(
    /([?&](?:token|secret|key|api_?key|access_token|auth|password|pwd)=)([^&\s"')]+)/gi,
    (_m, prefix) => `${prefix}${REDACTED}`,
  );
}

/**
 * Turns an unknown error into a sanitized, size-capped trace string suitable
 * for storing in a Google Sheets cell (Session Log `error_trace`).
 *
 * Guarantees:
 * - Never contains the bot token, service-account private key material,
 *   Gemini API key, or webhook secrets.
 * - Query-string secrets in URLs are redacted.
 * - Output is capped to MAX_TRACE_LENGTH characters.
 */
export function sanitizeErrorTrace(error: unknown): string {
  let raw: string;
  if (error instanceof Error) {
    raw = error.stack ? `${error.message}\n${error.stack}` : error.message;
  } else if (typeof error === "string") {
    raw = error;
  } else {
    try {
      raw = JSON.stringify(error);
    } catch {
      raw = String(error);
    }
  }

  let sanitized = redactUrlQuerySecrets(raw);

  for (const secret of collectSecretValues()) {
    // Escape regex special chars in the secret before global replace.
    const escaped = secret.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    sanitized = sanitized.replace(new RegExp(escaped, "g"), REDACTED);
  }

  if (sanitized.length > MAX_TRACE_LENGTH) {
    sanitized = sanitized.slice(0, MAX_TRACE_LENGTH) + "…[truncated]";
  }

  return sanitized;
}
