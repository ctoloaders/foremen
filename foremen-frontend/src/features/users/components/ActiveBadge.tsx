import { useTranslation } from 'react-i18next'
import { Badge } from '@/components/ui/badge'

interface ActiveBadgeProps {
  active: boolean
}

export function ActiveBadge({ active }: ActiveBadgeProps) {
  const { t } = useTranslation()

  if (active) {
    return (
      <Badge className="border-transparent bg-[#22c55e]/15 text-[#22c55e]">
        {t('users.badge.active')}
      </Badge>
    )
  }

  return (
    <Badge variant="secondary">
      {t('users.badge.inactive')}
    </Badge>
  )
}
