import { useTranslation } from 'react-i18next'
import { Badge } from '@/components/ui/badge'

interface DefaultBadgeProps {
  isDefault: boolean
}

/**
 * Localized default/not-default badge for VAT rates. Renders the
 * `vatRates.badge.default` / `vatRates.badge.notDefault` keys.
 */
export function DefaultBadge({ isDefault }: DefaultBadgeProps) {
  const { t } = useTranslation()

  if (isDefault) {
    return (
      <Badge className="border-transparent bg-[#3b82f6]/15 text-[#3b82f6]">
        {t('vatRates.badge.default')}
      </Badge>
    )
  }

  return <Badge variant="secondary">{t('vatRates.badge.notDefault')}</Badge>
}
