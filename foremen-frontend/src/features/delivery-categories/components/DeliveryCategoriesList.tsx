import { useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { Plus, Pencil, Trash2 } from 'lucide-react'

import { DataTable } from '@/components/data-table'
import type {
  ColumnConfig,
  FetchParams,
  PaginatedResponse as DTPaginatedResponse,
} from '@/components/data-table'
import { Button } from '@/components/ui/button'
import { usePermission } from '@/hooks/usePermission'
import { fetchDeliveryCategories } from '../api/delivery-categories-api'
import { ActiveBadge } from './ActiveBadge'
import type { DeliveryCategoryDto } from '../types'

interface DeliveryCategoriesListProps {
  onCreateDeliveryCategory: () => void
  onEditDeliveryCategory: (deliveryCategoryId: number) => void
  onDeleteDeliveryCategory: (deliveryCategory: DeliveryCategoryDto) => void
}

const columns: ColumnConfig<DeliveryCategoryDto>[] = [
  {
    field: 'code',
    headerKey: 'deliveryCategories.table.code',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '100px',
  },
  {
    field: 'name',
    headerKey: 'deliveryCategories.table.name',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '200px',
  },
  {
    field: 'active',
    headerKey: 'deliveryCategories.table.active',
    dataType: 'boolean',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '100px',
    render: (value) => <ActiveBadge active={value === true} />,
  },
]

export function DeliveryCategoriesList({
  onCreateDeliveryCategory,
  onEditDeliveryCategory,
  onDeleteDeliveryCategory,
}: DeliveryCategoriesListProps) {
  const { t } = useTranslation()
  const { hasPermission } = usePermission()

  // Permission gating for resource DELIVERY_CATEGORIES (FOR-03-07)
  const canCreate = hasPermission('DELIVERY_CATEGORIES', 'CREATE')
  const canUpdate = hasPermission('DELIVERY_CATEGORIES', 'UPDATE')
  const canDelete = hasPermission('DELIVERY_CATEGORIES', 'DELETE')

  // Adapter: bridges DataTable's FetchParams to the delivery-categories API (which
  // goes through the authenticated apiRequest client), then adds the first/last
  // fields DataTable expects.
  const fetchFn = useCallback(
    async (params: FetchParams): Promise<DTPaginatedResponse<DeliveryCategoryDto>> => {
      const data = await fetchDeliveryCategories({
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
  // Delivery categories are a plain dictionary: no system-delete guard — all rows
  // get delete when canDelete. Returns null when neither is permitted so DataTable
  // collapses the row-actions column.
  const rowActions = useCallback(
    (deliveryCategory: DeliveryCategoryDto) => {
      if (!canUpdate && !canDelete) return null
      return (
        <div className="flex items-center gap-1">
          {canUpdate && (
            <Button
              variant="ghost"
              size="icon"
              onClick={(e) => {
                e.stopPropagation()
                onEditDeliveryCategory(deliveryCategory.id)
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
                onDeleteDeliveryCategory(deliveryCategory)
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
    [onEditDeliveryCategory, onDeleteDeliveryCategory, t, canUpdate, canDelete],
  )

  return (
    <div className="space-y-4">
      {/* Create button above the table — rendered only with CREATE */}
      {canCreate && (
        <div className="flex justify-end">
          <Button onClick={onCreateDeliveryCategory}>
            <Plus className="mr-2 h-4 w-4" />
            {t('deliveryCategories.actions.create')}
          </Button>
        </div>
      )}

      {/* DataTable with full search, sort, filter, pagination */}
      <DataTable<DeliveryCategoryDto>
        entityKey="delivery-categories"
        resource="DELIVERY_CATEGORIES"
        columns={columns}
        fetchFn={fetchFn}
        defaultPageSize={10}
        pageSizeOptions={[10, 25, 50]}
        onRowClick={canUpdate ? (deliveryCategory) => onEditDeliveryCategory(deliveryCategory.id) : undefined}
        rowActions={rowActions}
      />
    </div>
  )
}
