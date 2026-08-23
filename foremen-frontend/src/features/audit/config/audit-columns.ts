import React from 'react'
import type { ColumnConfig } from '@/components/data-table/types'
import type { AuditRecord } from '../types'
import { ComparisonTable } from '@/features/audit/components/ComparisonTable'

export const auditFullColumns: ColumnConfig<AuditRecord>[] = [
  {
    field: 'id',
    headerKey: 'audit.column.id',
    dataType: 'number',
    sortable: true,
    filterable: true,
  },
  {
    field: 'entityClass',
    headerKey: 'audit.column.entityClass',
    dataType: 'string',
    sortable: true,
    filterable: true,
  },
  {
    field: 'entityId',
    headerKey: 'audit.column.entityId',
    dataType: 'number',
    sortable: true,
    filterable: true,
  },
  {
    field: 'operation',
    headerKey: 'audit.column.operation',
    dataType: 'string',
    sortable: true,
    filterable: true,
  },
  {
    field: 'performedBy',
    headerKey: 'audit.column.performedBy',
    dataType: 'string',
    sortable: true,
    filterable: true,
  },
  {
    field: 'performedAt',
    headerKey: 'audit.column.performedAt',
    dataType: 'date',
    sortable: true,
    filterable: true,
  },
  {
    field: 'changes',
    headerKey: 'audit.column.changes',
    dataType: 'string',
    sortable: false,
    filterable: false,
    searchable: false,
    render: (_value, row) => React.createElement(ComparisonTable, {
      snapshotBefore: row.snapshotBefore,
      snapshotAfter: row.snapshotAfter,
      operation: row.operation,
    }),
  },
]

/** Modal columns omit entityClass and entityId (constant in modal context) */
export const auditModalColumns: ColumnConfig<AuditRecord>[] = auditFullColumns.filter(
  (col) => col.field !== 'entityClass' && col.field !== 'entityId',
)
