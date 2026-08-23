import { Skeleton } from '@/components/ui/skeleton'
import { TableBody, TableRow, TableCell } from '@/components/ui/table'

interface DataTableSkeletonProps {
  columnCount: number
  rowCount?: number
}

export function DataTableSkeleton({ columnCount, rowCount = 5 }: DataTableSkeletonProps) {
  return (
    <TableBody>
      {Array.from({ length: rowCount }).map((_, rowIndex) => (
        <TableRow key={rowIndex}>
          {Array.from({ length: columnCount }).map((_, colIndex) => (
            <TableCell key={colIndex}>
              <Skeleton className="h-4 w-full bg-muted" />
            </TableCell>
          ))}
        </TableRow>
      ))}
    </TableBody>
  )
}
