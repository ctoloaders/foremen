/**
 * Generic retry utility for async operations.
 * Retries the given function N times with a fixed delay between attempts.
 * If all retries are exhausted, re-throws the last error.
 */
export async function withRetry<T>(
  fn: () => Promise<T>,
  retries: number = 1,
  delayMs: number = 2000
): Promise<T> {
  try {
    return await fn();
  } catch (error) {
    if (retries <= 0) throw error;
    await new Promise((r) => setTimeout(r, delayMs));
    return withRetry(fn, retries - 1, delayMs);
  }
}
