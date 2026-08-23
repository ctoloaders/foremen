import { TableBody, TableCell, TableRow } from '@/components/ui/table'

import type { ColumnConfig } from './types'
import { resolveFieldValue } from './utils/resolveFieldValue'

interface DataTableBodyProps<T> {
  data: T[]
  columns: ColumnConfig<T>[]
  onRowClick?: (row: T) => void
  rowActions?: (row: T) => React.ReactNode
}

export function DataTableBody<T>({
  data,
  columns,
  onRowClick,
  rowActions,
}: DataTableBodyProps<T>) {
  return (
    <TableBody>
      {data.map((row, rowIndex) => (
        <TableRow
          key={rowIndex}
          className={onRowClick ? 'cursor-pointer hover:bg-muted/50' : ''}
          onClick={() => onRowClick?.(row)}
        >
          {columns.map((col) => {
            const value = resolveFieldValue(row, col.field)
            return (
              <TableCell key={col.field}>
                {col.render ? col.render(value, row) : String(value ?? '')}
              </TableCell>
            )
          })}
          {rowActions && (
            <TableCell className="text-right">{rowActions(row)}</TableCell>
          )}
        </TableRow>
      ))}
    </TableBody>
  )
}
