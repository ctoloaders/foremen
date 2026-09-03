import { useEffect, useState } from 'react'

/**
 * Returns a debounced copy of `value` that only updates after `delayMs`
 * milliseconds have elapsed without `value` changing.
 *
 * Useful for deferring server-side search requests until the user pauses
 * typing (e.g. RoleSelect search → `useRolesInfinite(debouncedSearch)`).
 *
 * @param value   The value to debounce.
 * @param delayMs Debounce delay in milliseconds (default 300).
 */
export function useDebounce<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState<T>(value)

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs)
    return () => clearTimeout(timer)
  }, [value, delayMs])

  return debounced
}
