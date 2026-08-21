import { describe, it, expect, vi } from 'vitest'
import { renderHook } from '@testing-library/react'

vi.mock('react-router-dom', () => ({
  useLocation: vi.fn(),
}))

import { useLocation } from 'react-router-dom'
import { usePageMeta } from '../usePageMeta'

const mockUseLocation = vi.mocked(useLocation)

describe('usePageMeta', () => {
  it('returns titleKey "nav.dashboard" for pathname "/"', () => {
    mockUseLocation.mockReturnValue({ pathname: '/', search: '', hash: '', state: null, key: '' })
    const { result } = renderHook(() => usePageMeta())
    expect(result.current.titleKey).toBe('nav.dashboard')
  })

  it('returns titleKey "nav.projects" for pathname "/projects"', () => {
    mockUseLocation.mockReturnValue({ pathname: '/projects', search: '', hash: '', state: null, key: '' })
    const { result } = renderHook(() => usePageMeta())
    expect(result.current.titleKey).toBe('nav.projects')
  })

  it('returns fallback titleKey "nav.dashboard" for unknown pathname "/unknown"', () => {
    mockUseLocation.mockReturnValue({ pathname: '/unknown', search: '', hash: '', state: null, key: '' })
    const { result } = renderHook(() => usePageMeta())
    expect(result.current.titleKey).toBe('nav.dashboard')
  })

  it('action is undefined for standard routes', () => {
    mockUseLocation.mockReturnValue({ pathname: '/', search: '', hash: '', state: null, key: '' })
    const { result } = renderHook(() => usePageMeta())
    expect(result.current.action).toBeUndefined()
  })
})
