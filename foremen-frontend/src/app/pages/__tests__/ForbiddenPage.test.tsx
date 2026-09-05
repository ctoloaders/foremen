import { render, screen, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import ForbiddenPage from '@/app/pages/ForbiddenPage'
import i18n from '@/lib/i18n'
import {
  recordAllowedLocation,
  recordDeniedLocation,
} from '@/lib/last-allowed-location'

/**
 * Unit tests for the Forbidden_Page (`/403`, FOR-03-07 task 4.6).
 *
 * These assert BEHAVIOR against the real i18n instance and a real router:
 *  - the localized heading + Go_Back control render (Req 5.2, 5.3);
 *  - clicking Go_Back navigates to the target resolved by
 *    `resolveGoBackTarget()`: the Last_Allowed_Location normally (Req 5.4),
 *    `/` when there is none (Req 5.5), and `/` when it equals the
 *    Denied_Location (Req 5.6);
 *  - the strings resolve in both PL and RU (Req 7.1, 7.2).
 *
 * The Last_Allowed_Location module keeps its state in module-level variables,
 * so each test seeds it explicitly via `recordAllowedLocation` /
 * `recordDeniedLocation`. Navigation is captured by rendering a location
 * display route alongside the page inside a `MemoryRouter`.
 */

const LAST_ALLOWED = '/users?page=2'

/** Renders a route that echoes the current location for navigation assertions. */
function LocationDisplay() {
  const location = useLocation()
  return (
    <div data-testid="location-display">{`${location.pathname}${location.search}`}</div>
  )
}

function renderForbiddenPage() {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={['/403']}>
        <Routes>
          <Route path="/403" element={<ForbiddenPage />} />
          <Route path="/" element={<LocationDisplay />} />
          <Route path="/users" element={<LocationDisplay />} />
        </Routes>
      </MemoryRouter>
    </I18nextProvider>,
  )
}

async function setLanguage(lng: 'pl' | 'ru') {
  await act(async () => {
    await i18n.changeLanguage(lng)
  })
}

/**
 * Resets the Last_Allowed_Location module state between tests. There is no
 * exported reset, so seeding null-equivalent state is done by recording the
 * `/403` path (ignored by the module) for `lastAllowed` and clearing `denied`
 * with a value that never matches a seeded allowed location.
 */
function resetLocationState() {
  // `/403` is ignored by recordAllowedLocation, so lastAllowed stays null.
  recordAllowedLocation('/403')
  // Distinct sentinel so `lastAllowed === denied` is false unless a test sets it.
  recordDeniedLocation('__none__')
}

describe('ForbiddenPage', () => {
  beforeEach(async () => {
    resetLocationState()
    await setLanguage('pl')
  })

  afterEach(async () => {
    await setLanguage('pl')
  })

  it('renders the localized heading, message, and Go_Back control (PL)', () => {
    renderForbiddenPage()

    const heading = screen.getByRole('heading', { level: 1 })
    expect(heading).toHaveTextContent('Brak dostępu')
    expect(
      screen.getByText('Nie masz uprawnień do wyświetlenia tej strony.'),
    ).toBeInTheDocument()

    const goBack = screen.getByRole('button', { name: 'Wróć' })
    expect(goBack).toBeInTheDocument()
  })

  it('renders the strings in Russian when the active locale is ru', async () => {
    await setLanguage('ru')
    renderForbiddenPage()

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(
      'Нет доступа',
    )
    expect(
      screen.getByText('У вас нет прав для просмотра этой страницы.'),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Вернуться назад' }),
    ).toBeInTheDocument()
  })

  it('navigates to the Last_Allowed_Location when Go_Back is clicked', async () => {
    recordAllowedLocation(LAST_ALLOWED)
    renderForbiddenPage()

    await userEvent.click(screen.getByRole('button', { name: 'Wróć' }))

    expect(screen.getByTestId('location-display')).toHaveTextContent(
      LAST_ALLOWED,
    )
  })

  it('navigates to `/` when there is no Last_Allowed_Location', async () => {
    // resetLocationState leaves lastAllowed null.
    renderForbiddenPage()

    await userEvent.click(screen.getByRole('button', { name: 'Wróć' }))

    expect(screen.getByTestId('location-display')).toHaveTextContent('/')
  })

  it('navigates to `/` when the Last_Allowed_Location equals the Denied_Location', async () => {
    recordAllowedLocation(LAST_ALLOWED)
    recordDeniedLocation(LAST_ALLOWED)
    renderForbiddenPage()

    await userEvent.click(screen.getByRole('button', { name: 'Wróć' }))

    const display = screen.getByTestId('location-display')
    expect(display).toHaveTextContent('/')
    expect(display).not.toHaveTextContent('/users')
  })
})
