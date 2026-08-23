/**
 * Resolves a value from a nested object using dot-notation path.
 * Returns `undefined` if any intermediate key is null/undefined.
 *
 * @example
 * resolveFieldValue({ role: { name: "Admin" } }, "role.name") // "Admin"
 * resolveFieldValue({ role: null }, "role.name") // undefined
 */
export function resolveFieldValue(obj: unknown, path: string): unknown {
  const keys = path.split('.')
  let current: unknown = obj
  for (const key of keys) {
    if (current == null || typeof current !== 'object') return undefined
    current = (current as Record<string, unknown>)[key]
  }
  return current
}
