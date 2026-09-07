import { useTranslation } from 'react-i18next'

import type { ProjectMemberSummaryDto } from '../types'

interface ProjectMembersCellProps {
  members: ProjectMemberSummaryDto[] | null | undefined
}

/**
 * Renders the project team as one `"{userName} — {roleName}"` line per member
 * (FOR-04-13 Requirements 8.1, 8.2). When the project has no members, renders a
 * localized placeholder (`projects.members.empty`) instead of an empty cell.
 */
export function ProjectMembersCell({ members }: Readonly<ProjectMembersCellProps>) {
  const { t } = useTranslation()

  if (!members || members.length === 0) {
    return (
      <span className="text-muted-foreground">
        {t('projects.members.empty')}
      </span>
    )
  }

  return (
    <div className="flex flex-col gap-0.5">
      {members.map((member, index) => (
        <span key={member.userId ?? `${member.userName}-${index}`}>
          {member.userName} — {member.roleName}
        </span>
      ))}
    </div>
  )
}
