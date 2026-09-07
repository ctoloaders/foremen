/**
 * NumberInput — a shared, locale-tolerant decimal text input (FOR-04-bugs Bug 8 / Req 2.8).
 *
 * The native `<input type="number">` used by the room forms silently drops a comma
 * decimal separator: a user typing `12,5` (the RU/PL keyboard/locale convention) ends
 * up with a value that `Number()` / `z.coerce.number()` turns into `NaN`, so the metric
 * is lost. This component replaces those native number inputs with a controlled
 * `type="text" inputMode="decimal"` field that accepts BOTH `.` and `,` as the decimal
 * separator and normalizes `,` → `.` before emitting, so the value string handed to
 * react-hook-form / zod is always dot-based.
 *
 * Control contract (react-hook-form friendly):
 *   - `value`: the raw string currently shown (the parent keeps the string; numeric
 *     coercion happens at the zod layer on submit, matching the existing forms).
 *   - `onChange(next)`: emits the normalized string (comma → dot). Partial input the
 *     user is mid-typing is preserved (e.g. `12,` becomes `12.` and is NOT clobbered),
 *     so the caret and further typing behave naturally.
 *
 * Only characters valid for a decimal are accepted while typing: digits, a single
 * decimal separator, and — when `allowNegative` is set — a single leading `-`. Room
 * metrics are non-negative, so `allowNegative` defaults to `false`. Range/step
 * validation still lives in the zod schema; this component only governs the character
 * set and separator normalization, keeping the numeric payload identical for dot-input.
 */
import { forwardRef, useCallback } from 'react'

import { cn } from '@/lib/utils'

export interface NumberInputProps
  extends Omit<
    React.InputHTMLAttributes<HTMLInputElement>,
    'value' | 'onChange' | 'type'
  > {
  /** Raw string value shown in the field (dot-based after normalization). */
  value: string
  /** Emits the normalized (comma → dot) string as the user types. */
  onChange: (value: string) => void
  /** Allow a leading `-`. Defaults to `false` (room metrics are non-negative). */
  allowNegative?: boolean
}

/**
 * Normalize a raw text value to a dot-based decimal string, dropping characters that
 * can never be part of a valid decimal. Keeps partial input (a trailing separator, an
 * empty string, a lone `-`) intact so mid-typing values are not clobbered.
 */
export function normalizeDecimalInput(raw: string, allowNegative = false): string {
  // Any comma the user typed as a decimal separator becomes a dot.
  let s = raw.replace(/,/g, '.')

  // Keep only characters relevant to a decimal number.
  s = s.replace(allowNegative ? /[^0-9.-]/g : /[^0-9.]/g, '')

  // Collapse to a single leading '-' (only when negatives are allowed).
  if (allowNegative) {
    const negative = s.startsWith('-')
    s = s.replace(/-/g, '')
    if (negative) s = `-${s}`
  }

  // Collapse to a single decimal separator: keep the first '.', drop the rest.
  const firstDot = s.indexOf('.')
  if (firstDot !== -1) {
    s = s.slice(0, firstDot + 1) + s.slice(firstDot + 1).replace(/\./g, '')
  }

  return s
}

/**
 * A controlled decimal input. Use directly for string-state parents (RoomOpeningsEditor)
 * or inside a react-hook-form `Controller` (RoomFormSheet), passing `field.value` /
 * `field.onChange` through `value` / `onChange`.
 */
export const NumberInput = forwardRef<HTMLInputElement, NumberInputProps>(
  function NumberInput({ value, onChange, allowNegative = false, className, ...rest }, ref) {
    const handleChange = useCallback(
      (e: React.ChangeEvent<HTMLInputElement>) => {
        onChange(normalizeDecimalInput(e.target.value, allowNegative))
      },
      [onChange, allowNegative],
    )

    const handleBlur = useCallback(
      (e: React.FocusEvent<HTMLInputElement>) => {
        // Normalize once more on blur so a value pasted whole is dot-based too.
        const normalized = normalizeDecimalInput(e.target.value, allowNegative)
        if (normalized !== e.target.value) onChange(normalized)
        rest.onBlur?.(e)
      },
      [onChange, allowNegative, rest],
    )

    return (
      <input
        ref={ref}
        type="text"
        inputMode="decimal"
        value={value}
        onChange={handleChange}
        {...rest}
        onBlur={handleBlur}
        className={cn(className)}
      />
    )
  },
)
