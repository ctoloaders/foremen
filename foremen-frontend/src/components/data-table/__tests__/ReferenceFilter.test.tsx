import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import type { ReactElement } from 'react'

import type { PaginatedResponse, ReferenceInfo } from '../types'

/**
 * Tests for the {@link ReferenceFilter} component (FOR-04-01 task 6.5).
 *
 * Behavior under test:
 *  - empty-state renders the localized empty text when a page has no options;
 *  - options render by their locale-resolved `name`;
 *  - single mode: clicking an option emits `[id]` and closes the dropdown;
 *  - multi mode: clicking options toggles membership (checkboxes shown);
 *  - clicking an option name is the "select one" gesture (replace + close);
 *  - clicking a row checkbox is the "select several" gesture (toggle membership,
 *    clearing the filter when the last id is removed);
 *  - debounced search issues a fetch whose URL contains the tilde-wrapped
 *    `name~ct~<term>` grammar;
 *  - infinite scroll loads the next page (IntersectionObserver mocked to fire);
 *  - a 403 on the options query degrades to a disabled "no access" control and
 *    calls `onForbiddenChange(true)`.
 *
 * Plus a unit test of the exported {@link buildOptionsUrl} helper.
 */

// --- i18n: return the key verbatim so DOM assertions are stable. `apiRequest`
//     is mocked below so the real `@/lib/i18n` chain is never pulled in, but we
//     still stub `initReactI18next` defensively. ---
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
  initReactI18next: { type: '3rdParty', init: () => {} },
}))

// --- api-client: mock `apiRequest` (the network seam) but keep the REAL
//     `ApiError` so the component's `instanceof ApiError` 403 branch holds. ---
const mockApiRequest = vi.fn()
vi.mock('@/lib/api-client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api-client')>()
  return {
    ...actual,
    apiRequest: (...args: unknown[]) => mockApiRequest(...args),
  }
})

// --- Import after mocks ---
import { ApiError } from '@/lib/api-client'
import {
  ReferenceFilter,
  buildOptionsUrl,
  type ReferenceOption,
} from '../ReferenceFilter'

// --- Fixtures ---

const REFERENCE: ReferenceInfo = {
  targetResource: 'roles',
  optionsPath: '/api/roles',
  labelField: 'name',
  labelI18n: true,
  idPath: 'role.id',
}

function makePage(
  options: ReferenceOption[],
  { number = 0, last = true }: { number?: number; last?: boolean } = {},
): PaginatedResponse<ReferenceOption> {
  return {
    content: options,
    totalElements: options.length,
    totalPages: last ? number + 1 : number + 2,
    number,
    size: 20,
    first: number === 0,
    last,
  }
}

/** Render the component inside a fresh QueryClientProvider (retries off). */
function renderFilter(ui: ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>,
  )
}

// --- IntersectionObserver control: capture the callback so a test can fire an
//     intersection on demand (jsdom has no real IntersectionObserver). ---
let intersectionCallback: IntersectionObserverCallback | null = null
let observedElements: Element[] = []

class MockIntersectionObserver {
  constructor(cb: IntersectionObserverCallback) {
    intersectionCallback = cb
  }
  observe(el: Element) {
    observedElements.push(el)
  }
  unobserve() {}
  disconnect() {}
  takeRecords(): IntersectionObserverEntry[] {
    return []
  }
  root = null
  rootMargin = ''
  thresholds = []
}

/** Fire an intersection on the currently-observed sentinel. */
function fireIntersection() {
  const target = observedElements[observedElements.length - 1]
  intersectionCallback?.(
    [{ isIntersecting: true, target } as unknown as IntersectionObserverEntry],
    {} as IntersectionObserver,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  intersectionCallback = null
  observedElements = []
  vi.stubGlobal('IntersectionObserver', MockIntersectionObserver)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

// ---------------------------------------------------------------------------
// buildOptionsUrl (pure) — tilde-wrapped grammar, query only when searching
// ---------------------------------------------------------------------------

describe('buildOptionsUrl', () => {
  it('omits the query param when the search term is empty', () => {
    const url = buildOptionsUrl('/api/roles', 0, '')
    expect(url).toBe('/api/roles?page=0&size=20&sort=name%2Casc')
    expect(url).not.toContain('query=')
  })

  it('omits the query param for a whitespace-only search term', () => {
    const url = buildOptionsUrl('/api/roles', 0, '   ')
    expect(url).not.toContain('query=')
  })

  it('appends the tilde-wrapped name~ct~<term> query when searching', () => {
    const url = buildOptionsUrl('/api/roles', 0, 'man')
    // Literal tilde form on the wire (Requirement 3.2 grammar).
    expect(url).toContain('&query=name~ct~man')
    expect(url).not.toContain('name=ct=')
    expect(url).toContain('page=0')
  })

  it('reflects the requested page in the URL', () => {
    const url = buildOptionsUrl('/api/roles', 2, '')
    expect(url).toContain('page=2')
  })
})

// ---------------------------------------------------------------------------
// Component behavior
// ---------------------------------------------------------------------------

describe('ReferenceFilter — options rendering', () => {
  it('renders the localized empty-state when a page has no options', async () => {
    mockApiRequest.mockResolvedValue(makePage([]))

    renderFilter(<ReferenceFilter reference={REFERENCE} />)

    expect(await screen.findByText('referenceFilter.empty')).toBeInTheDocument()
  })

  it('renders every option with a name button and an always-visible checkbox', async () => {
    mockApiRequest.mockResolvedValue(
      makePage([
        { id: 1, name: 'Administrator' },
        { id: 2, name: 'Manager' },
      ]),
    )

    renderFilter(<ReferenceFilter reference={REFERENCE} />)

    // The name is a clickable button (the "select one" gesture).
    expect(await screen.findByRole('button', { name: 'Administrator' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Manager' })).toBeInTheDocument()
    // A square checkbox is always shown per row (the "select several" gesture) —
    // never a radio-style control, and present even with no active selection.
    expect(screen.getAllByRole('checkbox')).toHaveLength(2)
  })
})

describe('ReferenceFilter — "select one" via the name (Req 3.1, 3.4, 3.5)', () => {
  it('clicking an option name replaces the selection with [id] and closes the dropdown', async () => {
    mockApiRequest.mockResolvedValue(
      makePage([
        { id: 2, name: 'Manager' },
        { id: 3, name: 'Foreman' },
      ]),
    )
    const onChange = vi.fn()
    const onClose = vi.fn()
    const user = userEvent.setup()

    // Start with a multi selection to prove the name click RESETS it to one id.
    renderFilter(
      <ReferenceFilter
        reference={REFERENCE}
        value={[3]}
        onChange={onChange}
        onClose={onClose}
      />,
    )

    await user.click(await screen.findByRole('button', { name: 'Manager' }))

    expect(onChange).toHaveBeenCalledWith([2])
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})

describe('ReferenceFilter — "select several" via the checkbox (Req 4.1, 4.2, 4.4)', () => {
  it('toggles membership on checkbox click; removing the last id clears the filter', async () => {
    mockApiRequest.mockResolvedValue(
      makePage([
        { id: 2, name: 'Manager' },
        { id: 3, name: 'Foreman' },
      ]),
    )
    const onChange = vi.fn()
    const onClose = vi.fn()
    const user = userEvent.setup()

    // Start with Manager (2) selected so a second checkbox appends and a repeat
    // click on Manager's checkbox removes the last id (empty → filter dropped).
    renderFilter(
      <ReferenceFilter
        reference={REFERENCE}
        value={[2]}
        onChange={onChange}
        onClose={onClose}
      />,
    )

    const checkboxes = await screen.findAllByRole('checkbox')
    // Row order matches option order: [Manager(2), Foreman(3)].
    const [managerBox, foremanBox] = checkboxes

    // Checking Foreman appends its id (multi), and does NOT close the dropdown.
    await user.click(foremanBox!)
    expect(onChange).toHaveBeenLastCalledWith([2, 3])
    expect(onClose).not.toHaveBeenCalled()

    // Unchecking the already-selected Manager removes the last id → empty.
    await user.click(managerBox!)
    expect(onChange).toHaveBeenLastCalledWith([])
  })

  it('checking a box when nothing is selected adds the first id', async () => {
    mockApiRequest.mockResolvedValue(makePage([{ id: 9, name: 'Vendor' }]))
    const onChange = vi.fn()
    const user = userEvent.setup()

    renderFilter(<ReferenceFilter reference={REFERENCE} value={[]} onChange={onChange} />)

    await user.click((await screen.findAllByRole('checkbox'))[0]!)
    expect(onChange).toHaveBeenCalledWith([9])
  })
})

describe('ReferenceFilter — debounced search (Req 3.2, 3.3)', () => {
  it('issues a fetch whose URL contains the tilde-wrapped name~ct~<term>', async () => {
    mockApiRequest.mockResolvedValue(makePage([{ id: 2, name: 'Manager' }]))
    const user = userEvent.setup()

    renderFilter(<ReferenceFilter reference={REFERENCE} />)

    // Wait for the initial (empty-search) fetch to settle.
    await screen.findByRole('button', { name: 'Manager' })
    mockApiRequest.mockClear()

    await user.type(screen.getByPlaceholderText('referenceFilter.searchPlaceholder'), 'man')

    // After the 300ms debounce, a fetch keyed on the search term fires with the
    // tilde-wrapped grammar in the URL.
    await waitFor(() => {
      const calledWithTilde = mockApiRequest.mock.calls.some(
        ([url]) => typeof url === 'string' && url.includes('query=name~ct~man'),
      )
      expect(calledWithTilde).toBe(true)
    })

    // Never the equals-wrapped form.
    const anyEqualsForm = mockApiRequest.mock.calls.some(
      ([url]) => typeof url === 'string' && url.includes('name=ct='),
    )
    expect(anyEqualsForm).toBe(false)
  })
})

describe('ReferenceFilter — infinite scroll (Req 3.3)', () => {
  it('loads the next page when the sentinel intersects', async () => {
    mockApiRequest
      .mockResolvedValueOnce(makePage([{ id: 1, name: 'Alpha' }], { number: 0, last: false }))
      .mockResolvedValueOnce(makePage([{ id: 2, name: 'Beta' }], { number: 1, last: true }))

    renderFilter(<ReferenceFilter reference={REFERENCE} />)

    // First page rendered.
    await screen.findByRole('button', { name: 'Alpha' })
    expect(mockApiRequest).toHaveBeenCalledTimes(1)
    expect(mockApiRequest.mock.calls[0]?.[0]).toContain('page=0')

    // Fire an intersection on the sentinel → fetch page 1.
    fireIntersection()

    await screen.findByRole('button', { name: 'Beta' })
    expect(mockApiRequest).toHaveBeenCalledTimes(2)
    expect(mockApiRequest.mock.calls[1]?.[0]).toContain('page=1')
  })
})

describe('ReferenceFilter — 403 degradation (Req 5.5)', () => {
  it('renders the disabled no-access hint and calls onForbiddenChange(true)', async () => {
    mockApiRequest.mockRejectedValue(new ApiError(403, 'Forbidden'))
    const onForbiddenChange = vi.fn()

    const { container } = renderFilter(
      <ReferenceFilter reference={REFERENCE} onForbiddenChange={onForbiddenChange} />,
    )

    // The localized "no access" hint renders...
    expect(await screen.findByText('referenceFilter.noAccess')).toBeInTheDocument()

    // ...inside a disabled/forbidden control, with no interactive search/options.
    const forbidden = container.querySelector('[data-forbidden="true"]')
    expect(forbidden).not.toBeNull()
    expect(forbidden).toHaveAttribute('aria-disabled', 'true')
    expect(
      screen.queryByPlaceholderText('referenceFilter.searchPlaceholder'),
    ).not.toBeInTheDocument()
    expect(screen.queryByRole('option')).not.toBeInTheDocument()

    // The owner is notified of the forbidden state.
    await waitFor(() => {
      expect(onForbiddenChange).toHaveBeenCalledWith(true)
    })
  })
})
