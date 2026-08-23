// Task 8.1: Component tests for JsonExpander
// Requirements: 4.1, 4.2, 4.3, 4.4
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

// Mock react-i18next
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => {
      const map: Record<string, string> = {
        'audit.snapshot.show': 'Show',
        'audit.snapshot.hide': 'Hide',
      }
      return map[key] || key
    },
  }),
}))

import { JsonExpander } from '../JsonExpander'

describe('JsonExpander', () => {
  it('renders dash when data is null', () => {
    render(<JsonExpander data={null} />)

    const dash = screen.getByText('—')
    expect(dash).toBeInTheDocument()
  })

  it('renders dash when data is undefined', () => {
    render(<JsonExpander data={undefined} />)

    const dash = screen.getByText('—')
    expect(dash).toBeInTheDocument()
  })

  it('renders dash with muted styling for null/undefined', () => {
    const { container } = render(<JsonExpander data={null} />)

    const span = container.querySelector('span')
    expect(span).toHaveClass('text-muted-foreground')
  })

  it('renders "Show" button when data is non-null', () => {
    render(<JsonExpander data={{ name: 'test' }} />)

    const button = screen.getByRole('button', { name: 'Show' })
    expect(button).toBeInTheDocument()
  })

  it('click "Show" expands to formatted JSON in <pre>', async () => {
    const user = userEvent.setup()
    const testData = { name: 'Admin', code: 'ADM' }

    render(<JsonExpander data={testData} />)

    const showButton = screen.getByRole('button', { name: 'Show' })
    await user.click(showButton)

    const pre = document.querySelector('pre')
    expect(pre).toBeInTheDocument()
    expect(pre?.textContent).toBe(JSON.stringify(testData, null, 2))
  })

  it('click "Hide" collapses back to "Show" button', async () => {
    const user = userEvent.setup()
    const testData = { key: 'value' }

    render(<JsonExpander data={testData} />)

    // Expand
    await user.click(screen.getByRole('button', { name: 'Show' }))
    expect(document.querySelector('pre')).toBeInTheDocument()

    // Collapse
    await user.click(screen.getByRole('button', { name: 'Hide' }))
    expect(document.querySelector('pre')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Show' })).toBeInTheDocument()
  })
})
