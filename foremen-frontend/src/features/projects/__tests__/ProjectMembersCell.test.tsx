import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'

// --- i18n mock: resolve the placeholder key to a stable label for assertions ---
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => {
      const translations: Record<string, string> = {
        'projects.members.empty': 'No members',
      }
      return translations[key] ?? key
    },
  }),
}))

import { ProjectMembersCell } from '../components/ProjectMembersCell'
import type { ProjectMemberSummaryDto } from '../types'

function member(
  overrides: Partial<ProjectMemberSummaryDto> = {},
): ProjectMemberSummaryDto {
  return {
    userId: 1,
    userName: 'Anna Nowak',
    roleCode: 'MANAGER',
    roleName: 'Kierownik',
    ...overrides,
  }
}

describe('ProjectMembersCell', () => {
  it('renders one "{userName} — {roleName}" line per member', () => {
    const members: ProjectMemberSummaryDto[] = [
      member({ userId: 1, userName: 'Anna Nowak', roleName: 'Kierownik' }),
      member({ userId: 2, userName: 'Piotr Kowalski', roleName: 'Brygadzista' }),
      member({ userId: 3, userName: 'Jan Wiśniewski', roleName: 'Klient' }),
    ]

    render(<ProjectMembersCell members={members} />)

    expect(screen.getByText('Anna Nowak — Kierownik')).toBeInTheDocument()
    expect(screen.getByText('Piotr Kowalski — Brygadzista')).toBeInTheDocument()
    expect(screen.getByText('Jan Wiśniewski — Klient')).toBeInTheDocument()

    // The placeholder must not appear when members are present.
    expect(screen.queryByText('No members')).not.toBeInTheDocument()
  })

  it('renders exactly one line per member (row count == member count)', () => {
    const members: ProjectMemberSummaryDto[] = [
      member({ userId: 1, userName: 'A', roleName: 'R1' }),
      member({ userId: 2, userName: 'B', roleName: 'R2' }),
    ]

    render(<ProjectMembersCell members={members} />)

    const lines = screen.getAllByText(/ — /)
    expect(lines).toHaveLength(2)
  })

  it('renders the localized placeholder when members is an empty array', () => {
    render(<ProjectMembersCell members={[]} />)

    expect(screen.getByText('No members')).toBeInTheDocument()
    expect(screen.queryByText(/ — /)).not.toBeInTheDocument()
  })

  it('renders the localized placeholder when members is null', () => {
    render(<ProjectMembersCell members={null} />)

    expect(screen.getByText('No members')).toBeInTheDocument()
  })

  it('renders the localized placeholder when members is undefined', () => {
    render(<ProjectMembersCell members={undefined} />)

    expect(screen.getByText('No members')).toBeInTheDocument()
  })
})
