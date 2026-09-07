import { useTranslation } from 'react-i18next'
import { Badge } from '@/components/ui/badge'
import type { MeasureSource } from '../types'

interface RoomSourceBadgeProps {
  source: MeasureSource
}

/**
 * Localized source badge for a room MeasureValue. Renders the
 * `rooms.source.*` keys ("Calculated" for `CALCULATED`, "Manual" for `MANUAL`).
 * `CALCULATED` is emphasized (derived from geometry); `MANUAL` uses the muted
 * secondary variant. The `rooms.*` keys are added at PL/RU parity in task 11.6.
 */
export function RoomSourceBadge({ source }: Readonly<RoomSourceBadgeProps>) {
  const { t } = useTranslation()

  if (source === 'CALCULATED') {
    return (
      <Badge className="border-transparent bg-[#22c55e]/15 text-[#22c55e]">
        {t('rooms.source.calculated')}
      </Badge>
    )
  }

  return <Badge variant="secondary">{t('rooms.source.manual')}</Badge>
}
