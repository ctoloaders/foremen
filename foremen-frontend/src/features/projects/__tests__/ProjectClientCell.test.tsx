import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'

// --- i18n mock: resolve the placeholder key to a stable label for assertions ---
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => {
      const translations: Record<string, string> = {
        'projects.client.empty': 'No client',
      }
      return translations[key] ?? key
    },
  }),
}))

import { ProjectClientCell } from '../components/ProjectClientCell'
import type { ProjectMemberSummaryDto } from '../types'

const clientMember: ProjectMemberSummaryDto = {
  userId: 42,
  userName: 'Jan Wiśniewski',
  roleCode: 'CLIENT',
  roleName: 'Klient',
}

describe('ProjectClientCell', () => {
  it("renders only the CLIENT member's userName", () => {
    render(<ProjectClientCell client={clientMember} />)

    expect(screen.getByText('Jan Wiśniewski')).toBeInTheDocument()

    // Only the name is rendered — not the role name.
    expect(screen.queryByText('Klient')).not.toBeInTheDocument()
    expect(screen.queryByText('No client')).not.toBeInTheDocument()
  })

  it('renders the localized placeholder when client is null', () => {
    render(<ProjectClientCell client={null} />)

    expect(screen.getByText('No client')).toBeInTheDocument()
  })

  it('renders the localized placeholder when client is undefined', () => {
    render(<ProjectClientCell client={undefined} />)

    expect(screen.getByText('No client')).toBeInTheDocument()
  })

  it('renders the localized placeholder when the client has no userName', () => {
    render(
      <ProjectClientCell
        client={{ ...clientMember, userName: '' }}
      />,
    )

    expect(screen.getByText('No client')).toBeInTheDocument()
  })
})
