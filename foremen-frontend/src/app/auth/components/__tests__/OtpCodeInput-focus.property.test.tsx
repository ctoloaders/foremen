// Feature: FOR-03-06-frontend-auth, Property 5: OTP input focus advances on entry and retreats on backspace
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as fc from 'fast-check'
import { useState } from 'react'
import { render, screen, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

// --- i18n mock: return the key so DOM assertions are deterministic ---
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}))

import { OtpCodeInput } from '@/app/auth/components/OtpCodeInput'

const OTP_LENGTH = 6

/**
 * Controlled harness: the parent owns `value` and re-renders on every
 * `onChange`, mirroring how OtpLoginPage drives the input. This is required
 * for focus assertions because the boxes are controlled.
 */
function Harness() {
  const [value, setValue] = useState('')
  return <OtpCodeInput value={value} onChange={setValue} />
}

function getBoxes(): HTMLInputElement[] {
  // Every box is a text input (type="text"); with the i18n mock they share the
  // same aria-label, so we select them positionally by role.
  return screen.getAllByRole('textbox') as HTMLInputElement[]
}

function focusedIndex(boxes: HTMLInputElement[]): number {
  return boxes.findIndex((b) => b === document.activeElement)
}

afterEach(() => {
  cleanup()
})

/**
 * Feature: FOR-03-06-frontend-auth, Property 5: OTP input focus advances on
 * entry and retreats on backspace
 *
 * For any sequence of digit entries and backspaces into the 6-box OTP input,
 * focus SHALL move to box i+1 after a digit is entered in box i (for i < 5),
 * and SHALL move to box i-1 when Backspace is pressed in an empty box i
 * (for i > 0).
 *
 * **Validates: Requirements 7.5**
 */
describe('Feature: FOR-03-06-frontend-auth, Property 5: OTP input focus advances on entry and retreats on backspace', () => {
  it('advances focus to the next box after each digit entry', async () => {
    await fc.assert(
      fc.asyncProperty(
        // An arbitrary run of 1..6 digits typed sequentially into the input.
        fc.array(fc.integer({ min: 0, max: 9 }), {
          minLength: 1,
          maxLength: OTP_LENGTH,
        }),
        async (digits) => {
          const user = userEvent.setup()
          render(<Harness />)

          const boxes = getBoxes()
          expect(boxes).toHaveLength(OTP_LENGTH)

          // Start typing from the first box.
          await user.click(boxes[0]!)

          for (let i = 0; i < digits.length; i++) {
            await user.keyboard(String(digits[i]))

            const expectedFocus = Math.min(i + 1, OTP_LENGTH - 1)
            // After a digit in box i (i < 5), focus advances to box i+1;
            // once the last box is filled focus stays on the last box.
            expect(focusedIndex(getBoxes())).toBe(expectedFocus)
          }

          cleanup()
        },
      ),
      { numRuns: 100 },
    )
  }, 30000)

  it('retreats focus to the previous box on Backspace in an empty box', async () => {
    await fc.assert(
      fc.asyncProperty(
        // Fill the first `prefill` boxes, then delete them one Backspace at a
        // time. Each Backspace clears the current box; the next Backspace lands
        // on a now-empty box and must retreat focus.
        fc.integer({ min: 1, max: OTP_LENGTH }),
        async (prefill) => {
          const user = userEvent.setup()
          render(<Harness />)

          const boxes = getBoxes()
          await user.click(boxes[0]!)

          // Enter `prefill` digits: focus ends on box min(prefill, 5).
          for (let i = 0; i < prefill; i++) {
            await user.keyboard('7')
          }

          // After entry, focus is on the box after the last filled one, capped
          // at the last box. If all 6 are filled focus stays on box 5.
          let focus = focusedIndex(getBoxes())
          const landedOnFilledLast = prefill === OTP_LENGTH

          // First Backspace: if focus is on a filled box (all-filled case), it
          // clears that box (no retreat). Otherwise focus is on an empty box
          // and it retreats.
          await user.keyboard('{Backspace}')
          let curr = getBoxes()
          if (landedOnFilledLast) {
            // Cleared box 5 in place; focus remains on box 5.
            expect(focusedIndex(curr)).toBe(OTP_LENGTH - 1)
            focus = OTP_LENGTH - 1
          } else {
            // Empty box: retreat to previous and clear it.
            expect(focusedIndex(curr)).toBe(Math.max(focus - 1, 0))
            focus = Math.max(focus - 1, 0)
          }

          // Subsequent Backspaces always start on a now-empty box and retreat
          // until reaching box 0.
          while (focus > 0) {
            await user.keyboard('{Backspace}')
            curr = getBoxes()
            expect(focusedIndex(curr)).toBe(focus - 1)
            focus -= 1
          }

          cleanup()
        },
      ),
      { numRuns: 100 },
    )
  }, 30000)
})
