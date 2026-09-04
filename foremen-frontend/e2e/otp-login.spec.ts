/**
 * E2E — Client OTP login (FOR-03-06 task 21.2).
 *
 * Covers Req 7.2, 7.4, 7.7, 7.11 (and 12.4): the email step requests a code and
 * advances to the segmented code step; entering all six digits auto-submits
 * verification (without pressing the verify control); a successful verify
 * auto-logs-in and lands the user on the home route `/`.
 */
import { test, expect } from '@playwright/test'

import {
  installAuthMocks,
  clearStoredSession,
  readAccessToken,
} from './support/auth-mocks'

test.beforeEach(async ({ page }) => {
  await installAuthMocks(page)
  await clearStoredSession(page)
})

test('OTP email -> segmented code auto-submit -> authenticated on "/"', async ({ page }) => {
  await page.goto('/auth/otp')

  // Email step: enter an email and request a code (Req 7.1, 7.2).
  const emailField = page.getByLabel(/e-mail/i)
  await expect(emailField).toBeVisible()
  await emailField.fill('client@example.com')
  await page.getByRole('button', { name: /wyślij kod/i }).click()

  // Code step advanced (anti-enumeration silent success on otp/request 200).
  await expect(page.getByText(/wprowadź kod/i)).toBeVisible()

  // The six segmented boxes are shown (Req 7.4); they are the inputs inside the
  // OtpCodeInput fieldset.
  const digitBoxes = page.locator('fieldset input')
  await expect(digitBoxes).toHaveCount(6)

  // Type one digit into each box; when all six are filled the page
  // auto-submits verification without the verify control (Req 7.7).
  const code = '123456'
  for (let i = 0; i < 6; i += 1) {
    await digitBoxes.nth(i).fill(code[i])
  }

  // Auto-submit fires -> otp/verify 200 -> auto-login -> navigate to `/` (Req 7.11).
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByText('Foremen').first()).toBeVisible()
  expect(await readAccessToken(page)).not.toBeNull()
})

test('OTP code step distributes a pasted 6-digit code across all boxes', async ({ page }) => {
  await page.goto('/auth/otp')

  await page.getByLabel(/e-mail/i).fill('client@example.com')
  await page.getByRole('button', { name: /wyślij kod/i }).click()

  await expect(page.getByText(/wprowadź kod/i)).toBeVisible()

  const digitBoxes = page.locator('fieldset input')
  await expect(digitBoxes).toHaveCount(6)

  // Focus the first box and paste a full code via a synthetic paste event (so
  // the test does not depend on clipboard permissions); it should distribute
  // across all boxes and auto-submit (Req 7.6, 7.7).
  await digitBoxes.first().focus()
  await digitBoxes.first().evaluate((el) => {
    const dt = new DataTransfer()
    dt.setData('text', '654321')
    el.dispatchEvent(
      new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }),
    )
  })

  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByText('Foremen').first()).toBeVisible()
})
