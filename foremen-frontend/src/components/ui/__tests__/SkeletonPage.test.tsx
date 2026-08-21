import { render } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { SkeletonPage } from '@/components/ui/SkeletonPage'

describe('SkeletonPage', () => {
  it('renders without crashing', () => {
    const { container } = render(<SkeletonPage />)
    expect(container.firstChild).toBeInTheDocument()
  })

  it('renders 4 placeholder cards with animate-pulse elements', () => {
    const { container } = render(<SkeletonPage />)
    // The grid contains 4 card containers, each with animate-pulse elements inside
    const grid = container.querySelector('.grid')
    expect(grid).not.toBeNull()
    const cards = grid!.children
    expect(cards).toHaveLength(4)
    // Each card should contain animate-pulse elements
    Array.from(cards).forEach((card) => {
      const pulseElements = card.querySelectorAll('.animate-pulse')
      expect(pulseElements.length).toBeGreaterThan(0)
    })
  })
})
