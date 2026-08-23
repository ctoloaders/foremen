import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

// Mock react-i18next
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}))

import { ComparisonTable } from '../components/ComparisonTable'
import { auditFullColumns } from '../config/audit-columns'

describe('ComparisonTable', () => {
  it('renders three-column header (Field, Value Before, Value After) for UPDATE', () => {
    render(
      <ComparisonTable
        snapshotBefore={{ name: 'old' }}
        snapshotAfter={{ name: 'new' }}
        operation="UPDATE"
      />,
    )
    expect(screen.getByText('audit.comparison.fieldHeader')).toBeInTheDocument()
    expect(screen.getByText('audit.comparison.valueBefore')).toBeInTheDocument()
    expect(screen.getByText('audit.comparison.valueAfter')).toBeInTheDocument()
  })

  it('CREATE operation shows green "New" badge, no table', () => {
    render(
      <ComparisonTable
        snapshotBefore={null}
        snapshotAfter={{ name: 'test' }}
        operation="CREATE"
      />,
    )
    expect(screen.getByText('audit.comparison.badgeNew')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('DELETE operation shows red "Deleted" badge, no table', () => {
    render(
      <ComparisonTable
        snapshotBefore={{ name: 'test' }}
        snapshotAfter={null}
        operation="DELETE"
      />,
    )
    expect(screen.getByText('audit.comparison.badgeDeleted')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('Error snapshot shows "Error" badge with error message text', () => {
    render(
      <ComparisonTable
        snapshotBefore={null}
        snapshotAfter={{ class: 'SomeClass', error: 'serialization_failed' }}
        operation="UPDATE"
      />,
    )
    expect(screen.getByText('audit.comparison.badgeError')).toBeInTheDocument()
    expect(screen.getByText('serialization_failed')).toBeInTheDocument()
  })

  it('Toggle between "changes only" and "all fields" shows/hides unchanged rows', () => {
    render(
      <ComparisonTable
        snapshotBefore={{ a: 1, b: 2 }}
        snapshotAfter={{ a: 1, b: 3 }}
        operation="UPDATE"
      />,
    )
    // Initially only 'b' is shown (changed), 'a' is hidden (unchanged)
    expect(screen.getByText('b')).toBeInTheDocument()
    expect(screen.queryByText('a')).not.toBeInTheDocument()
    // Click toggle to show all fields
    fireEvent.click(screen.getByText('audit.comparison.showAll'))
    // Now 'a' should be visible
    expect(screen.getByText('a')).toBeInTheDocument()
  })

  it('"No changes" message when snapshotBefore equals snapshotAfter', () => {
    render(
      <ComparisonTable
        snapshotBefore={{ a: 1 }}
        snapshotAfter={{ a: 1 }}
        operation="UPDATE"
      />,
    )
    expect(screen.getByText('audit.comparison.noChanges')).toBeInTheDocument()
  })

  it('Column config (auditFullColumns) has single "Changes" column replacing snapshotBefore/snapshotAfter', () => {
    const fields = auditFullColumns.map((c) => c.field)
    expect(fields).toContain('changes')
    expect(fields).not.toContain('snapshotBefore')
    expect(fields).not.toContain('snapshotAfter')
  })
})
