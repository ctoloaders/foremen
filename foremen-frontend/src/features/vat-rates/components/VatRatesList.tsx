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
import { fetchVatRates } from '../api/vat-rates-api'
import { ActiveBadge } from './ActiveBadge'
import { DefaultBadge } from './DefaultBadge'
import type { VatRateDto } from '../types'

interface VatRatesListProps {
  onCreateVatRate: () => void
  onEditVatRate: (vatRateId: number) => void
  onDeleteVatRate: (vatRate: VatRateDto) => void
}

const columns: ColumnConfig<VatRateDto>[] = [
  {
    field: 'code',
    headerKey: 'vatRates.table.code',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '100px',
  },
  {
    field: 'rate',
    headerKey: 'vatRates.table.rate',
    dataType: 'number',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '80px',
  },
  {
    field: 'name',
    headerKey: 'vatRates.table.name',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '200px',
  },
  {
    field: 'isDefault',
    headerKey: 'vatRates.table.isDefault',
    dataType: 'boolean',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '100px',
    render: (value) => <DefaultBadge isDefault={value === true} />,
  },
  {
    field: 'active',
    headerKey: 'vatRates.table.active',
    dataType: 'boolean',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '100px',
    render: (value) => <ActiveBadge active={value === true} />,
  },
]

export function VatRatesList({
  onCreateVatRate,
  onEditVatRate,
  onDeleteVatRate,
}: VatRatesListProps) {
  const { t } = useTranslation()
  const { hasPermission } = usePermission()

  // Permission gating for resource VAT_RATES (FOR-03-07)
  const canCreate = hasPermission('VAT_RATES', 'CREATE')
  const canUpdate = hasPermission('VAT_RATES', 'UPDATE')
  const canDelete = hasPermission('VAT_RATES', 'DELETE')

  // Adapter: bridges DataTable's FetchParams to the vat-rates API (which goes
  // through the authenticated apiRequest client), then adds the first/last
  // fields DataTable expects.
  const fetchFn = useCallback(
    async (params: FetchParams): Promise<DTPaginatedResponse<VatRateDto>> => {
      const data = await fetchVatRates({
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
  // VAT rates are a plain dictionary: no system-delete guard — all rows get
  // delete when canDelete. Returns null when neither is permitted so DataTable
  // collapses the row-actions column.
  const rowActions = useCallback(
    (vatRate: VatRateDto) => {
      if (!canUpdate && !canDelete) return null
      return (
        <div className="flex items-center gap-1">
          {canUpdate && (
            <Button
              variant="ghost"
              size="icon"
              onClick={(e) => {
                e.stopPropagation()
                onEditVatRate(vatRate.id)
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
                onDeleteVatRate(vatRate)
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
    [onEditVatRate, onDeleteVatRate, t, canUpdate, canDelete],
  )

  return (
    <div className="space-y-4">
      {/* Create button above the table — rendered only with CREATE */}
      {canCreate && (
        <div className="flex justify-end">
          <Button onClick={onCreateVatRate}>
            <Plus className="mr-2 h-4 w-4" />
            {t('vatRates.actions.create')}
          </Button>
        </div>
      )}

      {/* DataTable with full search, sort, filter, pagination */}
      <DataTable<VatRateDto>
        entityKey="vat-rates"
        resource="VAT_RATES"
        columns={columns}
        fetchFn={fetchFn}
        defaultPageSize={10}
        pageSizeOptions={[10, 25, 50]}
        onRowClick={canUpdate ? (vatRate) => onEditVatRate(vatRate.id) : undefined}
        rowActions={rowActions}
      />
    </div>
  )
}
