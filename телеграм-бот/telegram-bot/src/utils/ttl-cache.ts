import { logger } from "./logger.js";

/**
 * Tiny in-memory TTL cache for slow-changing registry data (workers, projects, access,
 * categories). It exists to keep Google Sheets reads off the user-facing hot path.
 *
 * - Concurrent callers for the same key share ONE in-flight load (no thundering herd).
 * - If a refresh fails (e.g. Sheets 429 quota) and a previous value exists, the stale value is
 *   served instead of failing the user request.
 * - TTL comes from REGISTRY_CACHE_TTL_MS (default 60 s; 0 disables caching entirely).
 */

interface Entry {
  value?: unknown;
  hasValue: boolean;
  expires: number;
  inflight?: Promise<unknown>;
}

const store = new Map<string, Entry>();

const DEFAULT_TTL_MS = 60_000;

/** Reads the configured TTL at call time (so tests/ops can change it via env). */
export function registryCacheTtlMs(): number {
  const raw = process.env.REGISTRY_CACHE_TTL_MS;
  if (raw === undefined || raw === "") return DEFAULT_TTL_MS;
  const n = Number(raw);
  return Number.isFinite(n) && n >= 0 ? n : DEFAULT_TTL_MS;
}

/** Drops one cached key (or everything when no key is given). */
export function invalidateCache(key?: string): void {
  if (key === undefined) store.clear();
  else store.delete(key);
}

/**
 * Returns the cached value for `key` if still fresh, otherwise runs `loader` (deduplicated) and
 * caches its result for `ttlMs`.
 */
export async function cached<T>(
  key: string,
  loader: () => Promise<T>,
  ttlMs: number = registryCacheTtlMs(),
): Promise<T> {
  if (ttlMs <= 0) return loader();

  const now = Date.now();
  const existing = store.get(key);

  if (existing?.hasValue && existing.expires > now) return existing.value as T;
  if (existing?.inflight) return existing.inflight as Promise<T>;

  const entry: Entry = existing ?? { hasValue: false, expires: 0 };

  const promise = loader()
    .then((value) => {
      entry.value = value;
      entry.hasValue = true;
      entry.expires = Date.now() + ttlMs;
      entry.inflight = undefined;
      return value;
    })
    .catch((err) => {
      entry.inflight = undefined;
      if (entry.hasValue) {
        // Serve the stale value rather than failing the user on a transient refresh error.
        logger.warn("cache refresh failed, serving stale value", {
          key,
          error: (err as Error)?.message,
        });
        entry.expires = Date.now() + Math.min(ttlMs, 10_000); // retry soon, not every request
        return entry.value as T;
      }
      throw err;
    });

  entry.inflight = promise;
  store.set(key, entry);
  return promise;
}
