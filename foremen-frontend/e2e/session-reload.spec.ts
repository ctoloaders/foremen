/**
 * E2E — Session hydration on reload (FOR-03-06 task 21.2).
 *
 * Covers Req 9.4 (and 4.1–4.4): reloading on a protected route with a valid
 * stored session stays authenticated; reloading with no stored token redirects
 * to `/login`.
 */
import { test, expect } from '@playwright/test'

import {
  installAuthMocks,
  clearStoredSession,
  seedStoredSession,
} from './support/auth-mocks'

test.beforeEach(async ({ page }) => {
  await installAuthMocks(page)
})

test('reload on a protected route with a valid stored session stays authenticated', async ({
  page,
}) => {
  // Stored tokens + mocked /me => hydration lands authenticated (Req 4.1, 4.2).
  await seedStoredSession(page)

  await page.goto('/projects')
  await expect(page).toHaveURL(/\/projects$/)
  await expect(page.getByRole('main').getByRole('heading', { name: 'Projekty' })).toBeVisible()

  // Reloading the protected route keeps the session (hydration re-runs from
  // the persisted token) (Req 9.4).
  await page.reload()
  await expect(page).toHaveURL(/\/projects$/)
  await expect(page.getByRole('main').getByRole('heading', { name: 'Projekty' })).toBeVisible()
})

test('reload on a protected route with no stored token redirects to /login', async ({ page }) => {
  await clearStoredSession(page)

  await page.goto('/projects')

  // No token -> hydration resolves unauthenticated -> guard redirects (Req 4.4, 9.4).
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.locator('#login-email')).toBeVisible()
})

test('the guard shows a loading state while hydration is in flight and never flashes /login for a valid session', async ({
  page,
}) => {
  // Delay the /me response so we can observe that the guard does NOT redirect to
  // /login while hydration is pending (Req 4.5, 9.4).
  await seedStoredSession(page)
  await page.route('**/api/auth/me', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 400))
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { ETag: '"e2e-me-etag-v1"' },
      body: JSON.stringify({ id: 1, name: 'E2E User', email: 'e2e@example.com', roleCode: 'ADMIN', permissions: [] }),
    })
  })

  await page.goto('/projects')

  // While hydration is pending the loading indicator is shown (role=status),
  // and the URL never becomes /login.
  await expect(page.getByRole('status')).toBeVisible()
  await expect(page).not.toHaveURL(/\/login$/)

  // Once hydration resolves the protected route renders.
  await expect(page.getByRole('main').getByRole('heading', { name: 'Projekty' })).toBeVisible()
})
