import { useTranslation } from 'react-i18next'
import { Badge } from '@/components/ui/badge'

interface ActiveBadgeProps {
  active: boolean
}

/**
 * Localized active/inactive badge for material categories. Mirrors the Delivery
 * Categories feature's ActiveBadge but renders the `materialCategories.badge.*` keys.
 */
export function ActiveBadge({ active }: ActiveBadgeProps) {
  const { t } = useTranslation()

  if (active) {
    return (
      <Badge className="border-transparent bg-[#22c55e]/15 text-[#22c55e]">
        {t('materialCategories.badge.active')}
      </Badge>
    )
  }

  return <Badge variant="secondary">{t('materialCategories.badge.inactive')}</Badge>
}
