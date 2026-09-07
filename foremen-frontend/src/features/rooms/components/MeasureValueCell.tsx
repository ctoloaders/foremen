import { RoomSourceBadge } from './RoomSourceBadge'
import type { MeasureValue } from '../types'

interface MeasureValueCellProps {
  /** The `{ value, source }` metric to render. */
  measure: MeasureValue | null | undefined
}

/**
 * Renders a room MeasureValue as a numeric value plus a localized source badge
 * ("Calculated"/"Manual"). Used in the rooms DataTable for `floorArea`,
 * `wallArea`, and `perimeter`. When the value is absent, renders an em-dash
 * placeholder and no badge; when a value is present but the source is unknown,
 * the number is shown without a badge.
 */
export function MeasureValueCell({ measure }: Readonly<MeasureValueCellProps>) {
  if (measure?.value == null) {
    return <span className="text-muted-foreground">—</span>
  }

  return (
    <div className="flex items-center gap-2">
      <span>{measure.value}</span>
      {measure.source != null && <RoomSourceBadge source={measure.source} />}
    </div>
  )
}
