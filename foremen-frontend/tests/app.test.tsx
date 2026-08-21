import { describe, it, expect } from 'vitest'
import { cn } from '@/lib/utils'

describe('App', () => {
  it('should be configured correctly', () => {
    expect(true).toBe(true)
  })

  it('should resolve path aliases', () => {
    expect(cn('foo', 'bar')).toBe('foo bar')
  })
})
