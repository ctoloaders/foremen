import { useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { Plus, Pencil, Trash2 } from 'lucide-react'

import { DataTable } from '@/components/data-table'
import type {
  ColumnConfig,
  FetchParams,
  PaginatedResponse as DTPaginatedResponse,
  SortState,
} from '@/components/data-table'
import { Button } from '@/components/ui/button'
import { usePermission } from '@/hooks/usePermission'
import { fetchDeliveryStatuses } from '../api/delivery-statuses-api'
import { ActiveBadge } from './ActiveBadge'
import type { DeliveryStatusDto } from '../types'

interface DeliveryStatusesListProps {
  onCreateStatus: () => void
  onEditStatus: (statusId: number) => void
  onDeleteStatus: (status: DeliveryStatusDto) => void
}

const columns: ColumnConfig<DeliveryStatusDto>[] = [
  {
    field: 'orderNo',
    headerKey: 'deliveryStatuses.table.orderNo',
    dataType: 'number',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '80px',
  },
  {
    field: 'code',
    headerKey: 'deliveryStatuses.table.code',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '140px',
  },
  {
    field: 'name',
    headerKey: 'deliveryStatuses.table.name',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '240px',
  },
  {
    field: 'active',
    headerKey: 'deliveryStatuses.table.active',
    dataType: 'boolean',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '100px',
    render: (value) => <ActiveBadge active={value === true} />,
  },
]

// Default sort: order statuses by their orderNo ascending.
const defaultSort: SortState[] = [{ field: 'orderNo', direction: 'asc', priority: 0 }]

export function DeliveryStatusesList({
  onCreateStatus,
  onEditStatus,
  onDeleteStatus,
}: DeliveryStatusesListProps) {
  const { t } = useTranslation()
  const { hasPermission } = usePermission()

  // Permission gating for resource DELIVERY_STATUSES (FOR-03-07)
  const canCreate = hasPermission('DELIVERY_STATUSES', 'CREATE')
  const canUpdate = hasPermission('DELIVERY_STATUSES', 'UPDATE')
  const canDelete = hasPermission('DELIVERY_STATUSES', 'DELETE')

  // Adapter: bridges DataTable's FetchParams to the delivery-statuses API (which
  // goes through the authenticated apiRequest client), then adds the first/last
  // fields DataTable expects.
  const fetchFn = useCallback(
    async (params: FetchParams): Promise<DTPaginatedResponse<DeliveryStatusDto>> => {
      const data = await fetchDeliveryStatuses({
        page: params.page,
        size: params.size,
        query: params.query || undefined,
        sort: params.sort,
      })

      return {
        ...data,
        first: data.number === 0,
        last: data.number >= data.totalPages - 1,
      }
    },
    [],
  )

  // Row actions: edit (UPDATE) and delete (DELETE), each permission-gated.
  // Delivery statuses are a plain dictionary: no system-delete guard — all rows
  // get delete when canDelete. Returns null when neither is permitted so
  // DataTable collapses the row-actions column.
  const rowActions = useCallback(
    (status: DeliveryStatusDto) => {
      if (!canUpdate && !canDelete) return null
      return (
        <div className="flex items-center gap-1">
          {canUpdate && (
            <Button
              variant="ghost"
              size="icon"
              onClick={(e) => {
                e.stopPropagation()
                onEditStatus(status.id)
              }}
              aria-label={t('common.edit')}
            >
              <Pencil className="h-4 w-4" />
            </Button>
          )}
          {canDelete && (
            <Button
              variant="ghost"
              size="icon"
              onClick={(e) => {
                e.stopPropagation()
                onDeleteStatus(status)
              }}
              aria-label={t('common.delete')}
              className="text-destructive hover:text-destructive"
            >
              <Trash2 className="h-4 w-4" />
            </Button>
          )}
        </div>
      )
    },
    [onEditStatus, onDeleteStatus, t, canUpdate, canDelete],
  )

  return (
    <div className="space-y-4">
      {/* Create button above the table — rendered only with CREATE */}
      {canCreate && (
        <div className="flex justify-end">
          <Button onClick={onCreateStatus}>
            <Plus className="mr-2 h-4 w-4" />
            {t('deliveryStatuses.actions.create')}
          </Button>
        </div>
      )}

      {/* DataTable with full search, sort, filter, pagination */}
      <DataTable<DeliveryStatusDto>
        entityKey="delivery-statuses"
        resource="DELIVERY_STATUSES"
        columns={columns}
        fetchFn={fetchFn}
        defaultPageSize={10}
        pageSizeOptions={[10, 25, 50]}
        defaultSort={defaultSort}
        onRowClick={canUpdate ? (status) => onEditStatus(status.id) : undefined}
        rowActions={rowActions}
      />
    </div>
  )
}
