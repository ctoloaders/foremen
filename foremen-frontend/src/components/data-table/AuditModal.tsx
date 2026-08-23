import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Skeleton } from '@/components/ui/skeleton'
import { auditModalColumns } from '@/features/audit/config/audit-columns'
import type { AuditRecord } from '@/features/audit/types'

interface AuditModalProps {
  open: boolean
  onClose: () => void
  entityKey: string // API path segment (e.g., "roles")
  entityId: number
}

/**
 * Audit modal that fetches and displays audit records for a specific entity.
 *
 * Uses the existing AdminController endpoint:
 * GET /api/{entityKey}/audit/{entityId}
 * Returns List<AuditLogEntity> ordered by performedAt ASC.
 * We sort client-side by performedAt DESC for display (most recent first).
 */
export function AuditModal({ open, onClose, entityKey, entityId }: AuditModalProps) {
  const { t } = useTranslation()

  const { data, isLoading } = useQuery({
    queryKey: ['audit', entityKey, entityId],
    queryFn: async () => {
      const response = await fetch(`/api/${entityKey}/audit/${entityId}`)
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      return response.json() as Promise<AuditRecord[]>
    },
    enabled: open,
  })

  // Sort by performedAt descending (endpoint returns ASC, we reverse for display)
  const sortedData = data
    ? [...data].sort(
        (a, b) => new Date(b.performedAt).getTime() - new Date(a.performedAt).getTime(),
      )
    : []

  return (
    <Dialog open={open} onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-[90vw] max-h-[80vh] overflow-hidden flex flex-col">
        <DialogHeader>
          <DialogTitle>
            {t('audit.modal.title')} — {entityKey} #{entityId}
          </DialogTitle>
        </DialogHeader>
        <div className="flex-1 overflow-auto">
          {isLoading ? (
            <div className="space-y-2">
              {Array.from({ length: 5 }).map((_, i) => (
                <Skeleton key={i} className="h-10 w-full" />
              ))}
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  {auditModalColumns.map((col) => (
                    <TableHead key={col.field}>{t(col.headerKey)}</TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {sortedData.map((record) => (
                  <TableRow key={record.id}>
                    {auditModalColumns.map((col) => (
                      <TableCell key={col.field}>
                        {col.render
                          ? col.render(record[col.field as keyof AuditRecord], record)
                          : String(record[col.field as keyof AuditRecord] ?? '')}
                      </TableCell>
                    ))}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </div>
      </DialogContent>
    </Dialog>
  )
}
