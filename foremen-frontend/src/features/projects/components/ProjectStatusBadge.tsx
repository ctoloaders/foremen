import { useTranslation } from 'react-i18next'

import { Badge } from '@/components/ui/badge'
import type { ProjectStatus } from '../types'

interface ProjectStatusBadgeProps {
  status: ProjectStatus
}

/**
 * Per-status colour classes. Colours mirror the ActiveBadge convention of a
 * tinted background + matching text so each of the five statuses is visually
 * distinct. Labels themselves come from i18n keys `projects.status.*`
 * (defined at parity in ru.json / pl.json by task 11.7).
 */
const statusClasses: Record<ProjectStatus, string> = {
  // Neutral / not-yet-started
  DRAFT: 'border-transparent bg-muted text-muted-foreground',
  // Active / in progress — green
  ACTIVE: 'border-transparent bg-[#22c55e]/15 text-[#22c55e]',
  // Paused — amber
  ON_HOLD: 'border-transparent bg-[#f59e0b]/15 text-[#f59e0b]',
  // Finished — blue
  COMPLETED: 'border-transparent bg-[#3b82f6]/15 text-[#3b82f6]',
  // Cancelled — red
  CANCELLED: 'border-transparent bg-destructive/15 text-destructive',
}

/**
 * Renders exactly one localized badge for a project's {@link ProjectStatus}
 * (FOR-04-13 Requirements 8.1, 8.2). The label is resolved from the
 * `projects.status.<STATUS>` i18n key.
 */
export function ProjectStatusBadge({ status }: Readonly<ProjectStatusBadgeProps>) {
  const { t } = useTranslation()

  return (
    <Badge className={statusClasses[status]}>
      {t(`projects.status.${status}`)}
    </Badge>
  )
}
