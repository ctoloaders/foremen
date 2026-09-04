// Task 13.5: Unit tests for OtpCodeInput
// Requirements: 7.7 (auto-submit fires once when all 6 boxes filled),
//               7.20 (numeric input mode + one-time-code autofill attributes)
import { describe, it, expect, vi, afterEach } from 'vitest'
import { useState } from 'react'
import { render, screen, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

// i18n mock: return the key so DOM assertions are deterministic and the boxes
// share a stable (positional) accessible name.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}))

import { OtpCodeInput } from '@/app/auth/components/OtpCodeInput'

const OTP_LENGTH = 6

/**
 * Controlled harness mirroring how OtpLoginPage drives the input: the parent
 * owns `value` and re-renders on every `onChange`. `onComplete` is forwarded so
 * tests can count auto-submit invocations.
 */
function Harness({ onComplete }: { onComplete?: (code: string) => void }) {
  const [value, setValue] = useState('')
  return (
    <OtpCodeInput value={value} onChange={setValue} onComplete={onComplete} />
  )
}

function getBoxes(): HTMLInputElement[] {
  return screen.getAllByRole('textbox') as HTMLInputElement[]
}

afterEach(() => {
  cleanup()
})

describe('OtpCodeInput', () => {
  it('fires onComplete exactly once with the 6-digit code when all boxes are filled (Req 7.7)', async () => {
    const onComplete = vi.fn()
    const user = userEvent.setup()
    render(<Harness onComplete={onComplete} />)

    const boxes = getBoxes()
    expect(boxes).toHaveLength(OTP_LENGTH)

    await user.click(boxes[0]!)
    // Type all six digits sequentially; focus auto-advances between boxes.
    await user.keyboard('123456')

    expect(onComplete).toHaveBeenCalledTimes(1)
    expect(onComplete).toHaveBeenCalledWith('123456')
  })

  it('does not fire onComplete until all six boxes are filled (Req 7.7)', async () => {
    const onComplete = vi.fn()
    const user = userEvent.setup()
    render(<Harness onComplete={onComplete} />)

    await user.click(getBoxes()[0]!)
    // Only five digits: the code is incomplete.
    await user.keyboard('12345')

    expect(onComplete).not.toHaveBeenCalled()
  })

  it('exposes numeric input mode and one-time-code autofill on every box (Req 7.20)', () => {
    render(<Harness />)

    const boxes = getBoxes()
    expect(boxes).toHaveLength(OTP_LENGTH)

    for (const box of boxes) {
      expect(box).toHaveAttribute('inputMode', 'numeric')
      expect(box).toHaveAttribute('autoComplete', 'one-time-code')
    }
  })
})
