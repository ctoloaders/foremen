import { useTranslation } from 'react-i18next'

import type { ProjectMemberSummaryDto } from '../types'

interface ProjectClientCellProps {
  client: ProjectMemberSummaryDto | null | undefined
}

/**
 * Renders the project's CLIENT member name (FOR-04-13 Requirements 8.1, 8.2).
 * `client` is the backend-derived single member whose `roleCode == "CLIENT"`.
 * When the project has no client, renders a localized placeholder
 * (`projects.client.empty`) instead of an empty cell.
 */
export function ProjectClientCell({ client }: Readonly<ProjectClientCellProps>) {
  const { t } = useTranslation()

  if (!client?.userName) {
    return (
      <span className="text-muted-foreground">
        {t('projects.client.empty')}
      </span>
    )
  }

  return <span>{client.userName}</span>
}
