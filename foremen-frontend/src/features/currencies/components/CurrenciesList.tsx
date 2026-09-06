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
import { fetchCurrencies } from '../api/currencies-api'
import { ActiveBadge } from './ActiveBadge'
import type { CurrencyDto } from '../types'

interface CurrenciesListProps {
  onCreateCurrency: () => void
  onEditCurrency: (currencyId: number) => void
  onDeleteCurrency: (currency: CurrencyDto) => void
}

const columns: ColumnConfig<CurrencyDto>[] = [
  {
    field: 'code',
    headerKey: 'currencies.table.code',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '100px',
  },
  {
    field: 'symbol',
    headerKey: 'currencies.table.symbol',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '80px',
  },
  {
    field: 'name',
    headerKey: 'currencies.table.name',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '200px',
  },
  {
    field: 'active',
    headerKey: 'currencies.table.active',
    dataType: 'boolean',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '100px',
    render: (value) => <ActiveBadge active={value === true} />,
  },
]

export function CurrenciesList({
  onCreateCurrency,
  onEditCurrency,
  onDeleteCurrency,
}: CurrenciesListProps) {
  const { t } = useTranslation()
  const { hasPermission } = usePermission()

  // Permission gating for resource CURRENCIES (FOR-03-07)
  const canCreate = hasPermission('CURRENCIES', 'CREATE')
  const canUpdate = hasPermission('CURRENCIES', 'UPDATE')
  const canDelete = hasPermission('CURRENCIES', 'DELETE')

  // Adapter: bridges DataTable's FetchParams to the currencies API (which goes
  // through the authenticated apiRequest client), then adds the first/last
  // fields DataTable expects.
  const fetchFn = useCallback(
    async (params: FetchParams): Promise<DTPaginatedResponse<CurrencyDto>> => {
      const data = await fetchCurrencies({
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
  // Currencies are a plain dictionary: no system-delete guard — all rows get
  // delete when canDelete. Returns null when neither is permitted so DataTable
  // collapses the row-actions column.
  const rowActions = useCallback(
    (currency: CurrencyDto) => {
      if (!canUpdate && !canDelete) return null
      return (
        <div className="flex items-center gap-1">
          {canUpdate && (
            <Button
              variant="ghost"
              size="icon"
              onClick={(e) => {
                e.stopPropagation()
                onEditCurrency(currency.id)
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
                onDeleteCurrency(currency)
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
    [onEditCurrency, onDeleteCurrency, t, canUpdate, canDelete],
  )

  return (
    <div className="space-y-4">
      {/* Create button above the table — rendered only with CREATE */}
      {canCreate && (
        <div className="flex justify-end">
          <Button onClick={onCreateCurrency}>
            <Plus className="mr-2 h-4 w-4" />
            {t('currencies.actions.create')}
          </Button>
        </div>
      )}

      {/* DataTable with full search, sort, filter, pagination */}
      <DataTable<CurrencyDto>
        entityKey="currencies"
        resource="CURRENCIES"
        columns={columns}
        fetchFn={fetchFn}
        defaultPageSize={10}
        pageSizeOptions={[10, 25, 50]}
        onRowClick={canUpdate ? (currency) => onEditCurrency(currency.id) : undefined}
        rowActions={rowActions}
      />
    </div>
  )
}
