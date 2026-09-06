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
import { fetchWorkPrices } from '../api/work-prices-api'
import { CurrentBadge } from './CurrentBadge'
import type { WorkPriceDto } from '../types'

interface WorkPricesListProps {
  onCreatePrice: () => void
  onEditPrice: (priceId: number) => void
  onDeletePrice: (price: WorkPriceDto) => void
}

// Columns. The `workItem` and `currency` columns carry an inline `reference`
// descriptor (mirroring the backend MetadataResponse.ReferenceInfo) so the
// DataTable renders a ReferenceFilter for each, sourcing options from the
// referenced endpoints. The render fns show the server-resolved display values
// (workItemName / currencyCode). `current` renders a localized badge.
const columns: ColumnConfig<WorkPriceDto>[] = [
  {
    field: 'workItem',
    headerKey: 'workPrices.table.workItem',
    dataType: 'string',
    sortable: false,
    filterable: true,
    searchable: false,
    minWidth: '240px',
    reference: {
      targetResource: 'work-items',
      optionsPath: '/api/work-items',
      labelField: 'name',
      labelI18n: true,
      idPath: 'workItem.id',
    },
    render: (_value, row) => row.workItemName,
  },
  {
    field: 'currency',
    headerKey: 'workPrices.table.currency',
    dataType: 'string',
    sortable: false,
    filterable: true,
    searchable: false,
    minWidth: '120px',
    reference: {
      targetResource: 'currencies',
      optionsPath: '/api/currencies',
      labelField: 'code',
      labelI18n: false,
      idPath: 'currency.id',
    },
    render: (_value, row) => row.currencyCode,
  },
  {
    field: 'netPrice',
    headerKey: 'workPrices.table.netPrice',
    dataType: 'number',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '120px',
  },
  {
    field: 'validFrom',
    headerKey: 'workPrices.table.validFrom',
    dataType: 'date',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '140px',
  },
  {
    field: 'validTo',
    headerKey: 'workPrices.table.validTo',
    dataType: 'date',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '140px',
    render: (value) => (value ? String(value) : '—'),
  },
  {
    field: 'current',
    headerKey: 'workPrices.table.current',
    dataType: 'boolean',
    sortable: false,
    filterable: false,
    searchable: false,
    minWidth: '120px',
    render: (_value, row) => <CurrentBadge current={row.current} />,
  },
]

// Default sort: by validFrom descending (newest price first).
const defaultSort: SortState[] = [{ field: 'validFrom', direction: 'desc', priority: 0 }]

export function WorkPricesList({
  onCreatePrice,
  onEditPrice,
  onDeletePrice,
}: WorkPricesListProps) {
  const { t } = useTranslation()
  const { hasPermission } = usePermission()

  // Permission gating for resource WORK_PRICES (FOR-03-07).
  // MANAGER has CREATE/READ/UPDATE but NOT DELETE, so delete is hidden for MANAGER.
  const canCreate = hasPermission('WORK_PRICES', 'CREATE')
  const canUpdate = hasPermission('WORK_PRICES', 'UPDATE')
  const canDelete = hasPermission('WORK_PRICES', 'DELETE')

  // Adapter: bridges DataTable's FetchParams to the work-prices API (which goes
  // through the authenticated apiRequest client), then adds the first/last
  // fields DataTable expects.
  const fetchFn = useCallback(
    async (params: FetchParams): Promise<DTPaginatedResponse<WorkPriceDto>> => {
      const data = await fetchWorkPrices({
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

  const rowActions = useCallback(
    (price: WorkPriceDto) => {
      if (!canUpdate && !canDelete) return null
      return (
        <div className="flex items-center gap-1">
          {canUpdate && (
            <Button
              variant="ghost"
              size="icon"
              onClick={(e) => {
                e.stopPropagation()
                onEditPrice(price.id)
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
                onDeletePrice(price)
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
    [onEditPrice, onDeletePrice, t, canUpdate, canDelete],
  )

  return (
    <div className="space-y-4">
      {canCreate && (
        <div className="flex justify-end">
          <Button onClick={onCreatePrice}>
            <Plus className="mr-2 h-4 w-4" />
            {t('workPrices.actions.create')}
          </Button>
        </div>
      )}

      <DataTable<WorkPriceDto>
        entityKey="work-prices"
        resource="WORK_PRICES"
        columns={columns}
        fetchFn={fetchFn}
        defaultPageSize={10}
        pageSizeOptions={[10, 25, 50]}
        defaultSort={defaultSort}
        onRowClick={canUpdate ? (price) => onEditPrice(price.id) : undefined}
        rowActions={rowActions}
      />
    </div>
  )
}
