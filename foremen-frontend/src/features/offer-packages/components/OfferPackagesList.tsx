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
import { fetchOfferPackages } from '../api/offer-packages-api'
import { ActiveBadge } from './ActiveBadge'
import type { OfferPackageDto } from '../types'

interface OfferPackagesListProps {
  onCreatePackage: () => void
  onEditPackage: (packageId: number) => void
  onDeletePackage: (offerPackage: OfferPackageDto) => void
}

const columns: ColumnConfig<OfferPackageDto>[] = [
  {
    field: 'orderNo',
    headerKey: 'offerPackages.table.orderNo',
    dataType: 'number',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '80px',
  },
  {
    field: 'code',
    headerKey: 'offerPackages.table.code',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '140px',
  },
  {
    field: 'name',
    headerKey: 'offerPackages.table.name',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '240px',
  },
  {
    field: 'active',
    headerKey: 'offerPackages.table.active',
    dataType: 'boolean',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '100px',
    render: (value) => <ActiveBadge active={value === true} />,
  },
]

// Default sort: order packages by their orderNo ascending.
const defaultSort: SortState[] = [{ field: 'orderNo', direction: 'asc', priority: 0 }]

export function OfferPackagesList({
  onCreatePackage,
  onEditPackage,
  onDeletePackage,
}: OfferPackagesListProps) {
  const { t } = useTranslation()
  const { hasPermission } = usePermission()

  // Permission gating for resource OFFER_PACKAGES (FOR-03-07)
  const canCreate = hasPermission('OFFER_PACKAGES', 'CREATE')
  const canUpdate = hasPermission('OFFER_PACKAGES', 'UPDATE')
  const canDelete = hasPermission('OFFER_PACKAGES', 'DELETE')

  // Adapter: bridges DataTable's FetchParams to the offer-packages API (which
  // goes through the authenticated apiRequest client), then adds the first/last
  // fields DataTable expects.
  const fetchFn = useCallback(
    async (params: FetchParams): Promise<DTPaginatedResponse<OfferPackageDto>> => {
      const data = await fetchOfferPackages({
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
  // Offer packages are a plain dictionary: no system-delete guard — all rows
  // get delete when canDelete. Returns null when neither is permitted so
  // DataTable collapses the row-actions column.
  const rowActions = useCallback(
    (offerPackage: OfferPackageDto) => {
      if (!canUpdate && !canDelete) return null
      return (
        <div className="flex items-center gap-1">
          {canUpdate && (
            <Button
              variant="ghost"
              size="icon"
              onClick={(e) => {
                e.stopPropagation()
                onEditPackage(offerPackage.id)
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
                onDeletePackage(offerPackage)
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
    [onEditPackage, onDeletePackage, t, canUpdate, canDelete],
  )

  return (
    <div className="space-y-4">
      {/* Create button above the table — rendered only with CREATE */}
      {canCreate && (
        <div className="flex justify-end">
          <Button onClick={onCreatePackage}>
            <Plus className="mr-2 h-4 w-4" />
            {t('offerPackages.actions.create')}
          </Button>
        </div>
      )}

      {/* DataTable with full search, sort, filter, pagination */}
      <DataTable<OfferPackageDto>
        entityKey="offer-packages"
        resource="OFFER_PACKAGES"
        columns={columns}
        fetchFn={fetchFn}
        defaultPageSize={10}
        pageSizeOptions={[10, 25, 50]}
        defaultSort={defaultSort}
        onRowClick={canUpdate ? (offerPackage) => onEditPackage(offerPackage.id) : undefined}
        rowActions={rowActions}
      />
    </div>
  )
}
