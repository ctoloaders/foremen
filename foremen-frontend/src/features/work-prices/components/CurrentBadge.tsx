import { useTranslation } from 'react-i18next'
import { Badge } from '@/components/ui/badge'

interface CurrentBadgeProps {
  current: boolean
}

/**
 * Localized current/historic badge for work prices. Renders the
 * `workPrices.badge.*` keys. `current` is derived server-side (validTo == null).
 */
export function CurrentBadge({ current }: CurrentBadgeProps) {
  const { t } = useTranslation()

  if (current) {
    return (
      <Badge className="border-transparent bg-[#22c55e]/15 text-[#22c55e]">
        {t('workPrices.badge.current')}
      </Badge>
    )
  }

  return <Badge variant="secondary">{t('workPrices.badge.historic')}</Badge>
}
