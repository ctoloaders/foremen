/**
 * E2E — Deep-link preservation / Auth_Guard redirect round-trip
 * (FOR-03-06 task 21.2).
 *
 * Covers Req 9.2, 9.4, 12.1, 12.3: an unauthenticated deep-link to a protected
 * route is bounced to `/login` with the attempted location captured as the
 * Return_Location, and after a successful login the user is returned to the
 * original deep link.
 */
import { test, expect } from '@playwright/test'

import {
  installAuthMocks,
  clearStoredSession,
  readReturnLocation,
} from './support/auth-mocks'

test.beforeEach(async ({ page }) => {
  await installAuthMocks(page)
  await clearStoredSession(page)
})

test('deep-link to a protected route while unauthenticated redirects to /login and captures the location', async ({
  page,
}) => {
  await page.goto('/projects')

  // The guard redirects the unauthenticated user to /login (Req 9.2).
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.locator('#login-email')).toBeVisible()

  // The attempted protected location is captured as the Return_Location (Req 12.1).
  expect(await readReturnLocation(page)).toBe('/projects')
})

test('after logging in from a deep-link redirect the user is returned to the deep link', async ({
  page,
}) => {
  // Start at a protected deep link while unauthenticated.
  await page.goto('/projects')
  await expect(page).toHaveURL(/\/login$/)
  expect(await readReturnLocation(page)).toBe('/projects')

  // Log in.
  await page.locator('#login-email').fill('e2e@example.com')
  await page.locator('#login-password').fill('correct-horse-battery')
  await page.getByRole('button', { name: 'Zaloguj się', exact: true }).click()

  // The app returns the user to the originally requested deep link (Req 12.3).
  await expect(page).toHaveURL(/\/projects$/)
  await expect(page.getByRole('main').getByRole('heading', { name: 'Projekty' })).toBeVisible()

  // Return_Location has been consumed (Req 12.6).
  expect(await readReturnLocation(page)).toBeNull()
})

test('a deep-link redirect never captures a Public_Route as the Return_Location', async ({
  page,
}) => {
  // Navigating directly to /login must not capture /login as a return target.
  await page.goto('/login')
  await expect(page.locator('#login-email')).toBeVisible()
  expect(await readReturnLocation(page)).toBeNull()
})
