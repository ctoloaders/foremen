import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'

import { DataTable } from '@/components/data-table'
import { auditFullColumns } from './config/audit-columns'
import { fetchAuditRecords } from './api/audit-api'
import type { AuditRecord } from './types'
import type { ColumnConfig, SortState } from '@/components/data-table/types'

export default function AuditPage() {
  const { t } = useTranslation()

  // Override the operation column render to translate operation codes
  const columns: ColumnConfig<AuditRecord>[] = useMemo(
    () =>
      auditFullColumns.map((col) => {
        if (col.field === 'operation') {
          return {
            ...col,
            render: (value: unknown) => {
              const code = value as string
              return t(`audit.operation.${code}`, { defaultValue: code })
            },
          }
        }
        return col
      }),
    [t],
  )

  // Props that will be handled by DataTable after task 5.3 adds support.
  // Read-only page: no Create/Edit/Delete. The `resource="AUDIT"` prop gates the
  // composed audit button behind `hasPermission('AUDIT', 'READ')` (FOR-03-07).
  const extraProps = {
    showAuditButton: false,
    defaultSort: [{ field: 'performedAt', direction: 'desc', priority: 1 }] as SortState[],
  }

  return (
    <DataTable<AuditRecord>
      entityKey="audit"
      resource="AUDIT"
      columns={columns}
      fetchFn={fetchAuditRecords}
      {...extraProps}
    />
  )
}
