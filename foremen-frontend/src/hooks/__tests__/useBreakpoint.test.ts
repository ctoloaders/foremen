import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook } from '@testing-library/react'

import { useBreakpoint } from '../useBreakpoint'

function mockMatchMedia(mobile: boolean, tablet: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: query.includes('max-width: 767px')
        ? mobile
        : query.includes('min-width: 768px')
          ? tablet
          : false,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  })
}

describe('useBreakpoint', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('returns "mobile" when viewport < 768px', () => {
    mockMatchMedia(true, false)
    const { result } = renderHook(() => useBreakpoint())
    expect(result.current).toBe('mobile')
  })

  it('returns "tablet" when viewport 768-1024px', () => {
    mockMatchMedia(false, true)
    const { result } = renderHook(() => useBreakpoint())
    expect(result.current).toBe('tablet')
  })

  it('returns "desktop" when viewport > 1024px', () => {
    mockMatchMedia(false, false)
    const { result } = renderHook(() => useBreakpoint())
    expect(result.current).toBe('desktop')
  })
})
