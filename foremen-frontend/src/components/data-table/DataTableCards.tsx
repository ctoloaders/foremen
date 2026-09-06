import { useTranslation } from 'react-i18next'

import type { ColumnConfig } from './types'
import { resolveFieldValue } from './utils/resolveFieldValue'

interface DataTableCardsProps<T> {
  data: T[]
  columns: ColumnConfig<T>[]
  onRowClick?: (row: T) => void
  rowActions?: (row: T) => React.ReactNode
}

/** Consistent placeholder rendered for genuinely empty values. */
const EMPTY_VALUE_PLACEHOLDER = '—'

/**
 * A value is "empty" only when it is null, undefined, or an empty/whitespace
 * string. `false` and `0` are NOT empty — they are real values, so custom
 * renders (e.g. ActiveBadge for booleans) still run for them.
 */
function isEmptyValue(value: unknown): boolean {
  return (
    value == null || (typeof value === 'string' && value.trim() === '')
  )
}

export function DataTableCards<T>({
  data,
  columns,
  onRowClick,
  rowActions,
}: DataTableCardsProps<T>) {
  const { t } = useTranslation()

  return (
    <div className="space-y-3">
      {data.map((row, rowIndex) => (
        <div
          key={rowIndex}
          className={`flex min-h-[7.5rem] flex-col rounded-lg border bg-card p-4 ${onRowClick ? 'cursor-pointer hover:bg-muted/50' : ''}`}
          onClick={() => onRowClick?.(row)}
        >
          {/* Fixed template: uniform label→value grid so labels left-align in a
              consistent column and values align consistently across all cards. */}
          <dl className="grid flex-1 grid-cols-[minmax(6rem,auto)_1fr] items-baseline gap-x-3 gap-y-2">
            {columns.map((col) => {
              const value = resolveFieldValue(row, col.field)
              const empty = isEmptyValue(value)
              return (
                <div key={col.field} className="contents">
                  <dt className="min-w-0 truncate text-left text-sm text-muted-foreground">
                    {t(col.headerKey)}
                  </dt>
                  <dd className="min-w-0 truncate text-left text-sm font-medium">
                    {empty ? (
                      <span className="text-muted-foreground">
                        {EMPTY_VALUE_PLACEHOLDER}
                      </span>
                    ) : col.render ? (
                      col.render(value, row)
                    ) : (
                      String(value)
                    )}
                  </dd>
                </div>
              )
            })}
          </dl>
          {rowActions && (
            <div className="mt-3 flex justify-end border-t pt-3">
              {rowActions(row)}
            </div>
          )}
        </div>
      ))}
    </div>
  )
}
