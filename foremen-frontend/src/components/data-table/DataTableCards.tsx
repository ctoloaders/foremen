import { useTranslation } from 'react-i18next'

import type { ColumnConfig } from './types'
import { resolveFieldValue } from './utils/resolveFieldValue'

interface DataTableCardsProps<T> {
  data: T[]
  columns: ColumnConfig<T>[]
  onRowClick?: (row: T) => void
  rowActions?: (row: T) => React.ReactNode
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
          className={`rounded-lg border bg-card p-4 ${onRowClick ? 'cursor-pointer hover:bg-muted/50' : ''}`}
          onClick={() => onRowClick?.(row)}
        >
          <div className="space-y-2">
            {columns.map((col) => {
              const value = resolveFieldValue(row, col.field)
              return (
                <div
                  key={col.field}
                  className="flex items-center justify-between"
                >
                  <span className="text-sm text-muted-foreground">
                    {t(col.headerKey)}
                  </span>
                  <span className="text-sm font-medium">
                    {col.render ? col.render(value, row) : String(value ?? '')}
                  </span>
                </div>
              )
            })}
          </div>
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
