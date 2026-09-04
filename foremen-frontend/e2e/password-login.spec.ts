/**
 * E2E — Password login (FOR-03-06 task 21.2).
 *
 * Covers Req 12.1, 12.4 (and 5.4): a password login lands the user on the home
 * route `/` when there is no captured Return_Location, and on the captured
 * Return_Location when one is present.
 */
import { test, expect } from '@playwright/test'

import {
  installAuthMocks,
  clearStoredSession,
  seedStoredSession,
  readReturnLocation,
  readAccessToken,
  RETURN_LOCATION_KEY,
} from './support/auth-mocks'

test.beforeEach(async ({ page }) => {
  await installAuthMocks(page)
})

/** Fills the login form and submits it. */
async function submitLogin(page: import('@playwright/test').Page) {
  // Email + password are the only two text/password inputs on the LoginPage.
  await page.locator('#login-email').fill('e2e@example.com')
  await page.locator('#login-password').fill('correct-horse-battery')
  await page.getByRole('button', { name: 'Zaloguj się', exact: true }).click()
}

test('password login with no Return_Location lands on the home route "/"', async ({ page }) => {
  await clearStoredSession(page)

  await page.goto('/login')
  // The LoginPage renders its email/password fields.
  await expect(page.locator('#login-email')).toBeVisible()

  await submitLogin(page)

  // After login the app navigates to the home route and renders the protected
  // shell (the guard only lets authenticated users through).
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByText('Foremen').first()).toBeVisible()

  // The session token was persisted (Token_Storage).
  expect(await readAccessToken(page)).not.toBeNull()
})

test('password login returns the user to the captured Return_Location', async ({ page }) => {
  await clearStoredSession(page)

  // Seed a captured Return_Location as if the guard had bounced the user off a
  // protected deep link. `/projects` is a real protected route.
  await page.addInitScript((key) => {
    window.sessionStorage.setItem(key as string, '/projects')
  }, RETURN_LOCATION_KEY)

  await page.goto('/login')
  await expect(page.locator('#login-email')).toBeVisible()

  await submitLogin(page)

  // The app navigates to the captured deep link rather than `/`.
  await expect(page).toHaveURL(/\/projects$/)
  await expect(page.getByRole('main').getByRole('heading', { name: 'Projekty' })).toBeVisible()

  // Return_Location is consumed (cleared) after the redirect (Req 12.6).
  expect(await readReturnLocation(page)).toBeNull()
})

test('an already-authenticated user is redirected away from /login', async ({ page }) => {
  // A stored session + mocked /me makes hydration land authenticated (Req 5.8).
  await seedStoredSession(page)

  await page.goto('/login')

  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByText('Foremen').first()).toBeVisible()
})
