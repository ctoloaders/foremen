import {
  useCallback,
  useEffect,
  useId,
  useRef,
  type ChangeEvent,
  type ClipboardEvent,
  type KeyboardEvent,
} from 'react'
import { useTranslation } from 'react-i18next'

import { cn } from '@/lib/utils'

/** Number of digit boxes in the segmented one-time-code input. */
const OTP_LENGTH = 6

/** Keeps only decimal digits from an arbitrary string. */
function digitsOnly(raw: string): string {
  return raw.replace(/\D/g, '')
}

interface OtpCodeInputProps {
  /**
   * Controlled value of the code (0..6 decimal digits). The parent owns the
   * state; this component never keeps its own copy of the value.
   */
  value: string
  /** Called with the next full value whenever any box changes. */
  onChange: (value: string) => void
  /**
   * Called once with the complete 6-digit code when all boxes are filled,
   * enabling auto-submit. This fires as a side effect and does NOT move focus,
   * so it never traps the user inside the input (Req 7.7, 7.8, 11.7).
   */
  onComplete?: (code: string) => void
  /** Disables all boxes (e.g. while a verify request is in flight). */
  disabled?: boolean
  /**
   * Id of an external error region (e.g. an inline `role="alert"` message)
   * to link to the input group via `aria-describedby` so assistive technology
   * associates the error with the code input (Req 11.3).
   */
  'aria-describedby'?: string
  /**
   * Marks the group as invalid when a validation/backend error is present,
   * so assistive technology exposes the error state (Req 11.3).
   */
  'aria-invalid'?: boolean
  className?: string
}

/**
 * OtpCodeInput: a segmented 6-box one-time-code input (design "Pages and
 * components → OtpCodeInput").
 *
 * - 6 controlled single-digit boxes, one decimal digit each, with
 *   `inputMode="numeric"`, `autoComplete="one-time-code"`, and
 *   `pattern="[0-9]*"` so mobile shows a numeric keyboard and the platform may
 *   offer SMS/email autofill (Req 7.4, 7.20).
 * - Typing a digit advances focus to the next box; Backspace in an empty box
 *   retreats focus to the previous box (Req 7.5).
 * - Pasting distributes up to 6 digits across the boxes in order (Req 7.6).
 * - When all 6 boxes are filled, `onComplete` fires for auto-submit without
 *   moving focus, so focus is never trapped (Req 7.7, 7.8, 11.7).
 * - Each box carries an `aria-label` ("Digit N of 6") and the group is
 *   programmatically labeled via `aria-labelledby` (Req 11.7). Both labels are
 *   routed through i18n (`auth.otp.codeInput.groupLabel` /
 *   `auth.otp.codeInput.digitLabel`).
 * - Only numeric characters are accepted; non-digits are ignored on input and
 *   stripped on paste.
 */
export function OtpCodeInput({
  value,
  onChange,
  onComplete,
  disabled = false,
  'aria-describedby': ariaDescribedBy,
  'aria-invalid': ariaInvalid,
  className,
}: Readonly<OtpCodeInputProps>) {
  const { t } = useTranslation()
  const groupLabelId = useId()
  const inputsRef = useRef<(HTMLInputElement | null)[]>([])

  // Normalize the controlled value into exactly OTP_LENGTH slots.
  const sanitized = digitsOnly(value).slice(0, OTP_LENGTH)
  const boxes = Array.from(
    { length: OTP_LENGTH },
    (_, i) => sanitized[i] ?? '',
  )

  const focusBox = useCallback((index: number) => {
    const clamped = Math.min(Math.max(index, 0), OTP_LENGTH - 1)
    inputsRef.current[clamped]?.focus()
    inputsRef.current[clamped]?.select()
  }, [])

  // Auto-submit once all boxes are filled. Runs as an effect so it never
  // moves focus / traps the user inside the input (Req 7.7, 7.8, 11.7).
  useEffect(() => {
    if (sanitized.length === OTP_LENGTH) {
      onComplete?.(sanitized)
    }
    // Only re-run when the complete code changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sanitized])

  const handleChange = useCallback(
    (index: number, event: ChangeEvent<HTMLInputElement>) => {
      const incoming = digitsOnly(event.target.value)
      if (incoming.length === 0) {
        // Cleared the box (or typed a non-digit that got stripped).
        if (boxes[index] !== '') {
          const next = boxes.slice()
          next[index] = ''
          onChange(next.join(''))
        }
        return
      }

      // A single keystroke may deliver more than one digit (e.g. autofill or
      // fast typing); distribute from the current box forward.
      const next = boxes.slice()
      let cursor = index
      for (const digit of incoming) {
        if (cursor >= OTP_LENGTH) break
        next[cursor] = digit
        cursor += 1
      }
      onChange(next.join(''))
      focusBox(cursor >= OTP_LENGTH ? OTP_LENGTH - 1 : cursor)
    },
    [boxes, onChange, focusBox],
  )

  const handleKeyDown = useCallback(
    (index: number, event: KeyboardEvent<HTMLInputElement>) => {
      if (event.key === 'Backspace') {
        if (boxes[index] === '') {
          // Empty box: retreat focus to the previous box (Req 7.5).
          event.preventDefault()
          if (index > 0) {
            const next = boxes.slice()
            next[index - 1] = ''
            onChange(next.join(''))
            focusBox(index - 1)
          }
        }
        // Non-empty box: let the default clear the current box.
        return
      }

      if (event.key === 'ArrowLeft') {
        event.preventDefault()
        focusBox(index - 1)
        return
      }

      if (event.key === 'ArrowRight') {
        event.preventDefault()
        focusBox(index + 1)
      }
    },
    [boxes, onChange, focusBox],
  )

  const handlePaste = useCallback(
    (index: number, event: ClipboardEvent<HTMLInputElement>) => {
      event.preventDefault()
      const pasted = digitsOnly(event.clipboardData.getData('text'))
      if (pasted.length === 0) return

      // Distribute up to OTP_LENGTH digits starting at the focused box (Req 7.6).
      const next = boxes.slice()
      let cursor = index
      for (const digit of pasted) {
        if (cursor >= OTP_LENGTH) break
        next[cursor] = digit
        cursor += 1
      }
      onChange(next.join(''))
      focusBox(cursor >= OTP_LENGTH ? OTP_LENGTH - 1 : cursor)
    },
    [boxes, onChange, focusBox],
  )

  return (
    <fieldset
      aria-labelledby={groupLabelId}
      aria-describedby={ariaDescribedBy}
      aria-invalid={ariaInvalid || undefined}
      className={cn('flex items-center gap-2 border-0 p-0', className)}
    >
      <legend id={groupLabelId} className="sr-only">
        {t('auth.otp.codeInput.groupLabel')}
      </legend>
      {boxes.map((digit, index) => (
        <input
          // Boxes are positional and fixed in count, so the index key is stable.
          // eslint-disable-next-line react/no-array-index-key
          key={index}
          ref={(el) => {
            inputsRef.current[index] = el
          }}
          type="text"
          inputMode="numeric"
          autoComplete="one-time-code"
          pattern="[0-9]*"
          maxLength={1}
          disabled={disabled}
          value={digit}
          aria-label={t('auth.otp.codeInput.digitLabel', { index: index + 1 })}
          onChange={(event) => handleChange(index, event)}
          onKeyDown={(event) => handleKeyDown(index, event)}
          onPaste={(event) => handlePaste(index, event)}
          className={cn(
            'h-12 w-10 rounded-md border border-input bg-transparent text-center text-lg font-medium shadow-sm transition-colors',
            'focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring',
            'disabled:cursor-not-allowed disabled:opacity-50',
          )}
        />
      ))}
    </fieldset>
  )
}
