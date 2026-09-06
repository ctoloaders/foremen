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
import { fetchWorkItems } from '../api/work-catalog-api'
import { ActiveBadge } from './ActiveBadge'
import type { WorkItemDto } from '../types'

interface WorkCatalogListProps {
  onCreateItem: () => void
  onEditItem: (itemId: number) => void
  onDeleteItem: (item: WorkItemDto) => void
}

// Columns. The `workCategory` and `unit` columns carry an inline `reference`
// descriptor (mirroring the backend MetadataResponse.ReferenceInfo) so the
// DataTable renders a ReferenceFilter for each, sourcing options from the
// referenced dictionary endpoints. The render fns show the server-resolved
// localized display names (workCategoryName / unitName).
const columns: ColumnConfig<WorkItemDto>[] = [
  {
    field: 'name',
    headerKey: 'workCatalog.table.name',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '240px',
  },
  {
    field: 'workCategory',
    headerKey: 'workCatalog.table.workCategory',
    dataType: 'string',
    sortable: false,
    filterable: true,
    searchable: false,
    minWidth: '200px',
    reference: {
      targetResource: 'work-categories',
      optionsPath: '/api/work-categories',
      labelField: 'name',
      labelI18n: true,
      idPath: 'workCategory.id',
    },
    render: (_value, row) => row.workCategoryName,
  },
  {
    field: 'unit',
    headerKey: 'workCatalog.table.unit',
    dataType: 'string',
    sortable: false,
    filterable: true,
    searchable: false,
    minWidth: '160px',
    reference: {
      targetResource: 'measurement-units',
      optionsPath: '/api/measurement-units',
      labelField: 'name',
      labelI18n: true,
      idPath: 'unit.id',
    },
    render: (_value, row) => row.unitName,
  },
  {
    field: 'active',
    headerKey: 'workCatalog.table.active',
    dataType: 'boolean',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '100px',
    render: (value) => <ActiveBadge active={value === true} />,
  },
]

// Default sort: by name ascending.
const defaultSort: SortState[] = [{ field: 'name', direction: 'asc', priority: 0 }]

export function WorkCatalogList({
  onCreateItem,
  onEditItem,
  onDeleteItem,
}: WorkCatalogListProps) {
  const { t } = useTranslation()
  const { hasPermission } = usePermission()

  // Permission gating for resource WORK_CATALOG (FOR-03-07).
  // MANAGER has CREATE/READ/UPDATE but NOT DELETE, so delete is hidden for MANAGER.
  const canCreate = hasPermission('WORK_CATALOG', 'CREATE')
  const canUpdate = hasPermission('WORK_CATALOG', 'UPDATE')
  const canDelete = hasPermission('WORK_CATALOG', 'DELETE')

  // Adapter: bridges DataTable's FetchParams to the work-items API (which goes
  // through the authenticated apiRequest client), then adds the first/last
  // fields DataTable expects.
  const fetchFn = useCallback(
    async (params: FetchParams): Promise<DTPaginatedResponse<WorkItemDto>> => {
      const data = await fetchWorkItems({
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
    (item: WorkItemDto) => {
      if (!canUpdate && !canDelete) return null
      return (
        <div className="flex items-center gap-1">
          {canUpdate && (
            <Button
              variant="ghost"
              size="icon"
              onClick={(e) => {
                e.stopPropagation()
                onEditItem(item.id)
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
                onDeleteItem(item)
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
    [onEditItem, onDeleteItem, t, canUpdate, canDelete],
  )

  return (
    <div className="space-y-4">
      {canCreate && (
        <div className="flex justify-end">
          <Button onClick={onCreateItem}>
            <Plus className="mr-2 h-4 w-4" />
            {t('workCatalog.actions.create')}
          </Button>
        </div>
      )}

      <DataTable<WorkItemDto>
        entityKey="work-items"
        resource="WORK_CATALOG"
        columns={columns}
        fetchFn={fetchFn}
        defaultPageSize={10}
        pageSizeOptions={[10, 25, 50]}
        defaultSort={defaultSort}
        onRowClick={canUpdate ? (item) => onEditItem(item.id) : undefined}
        rowActions={rowActions}
      />
    </div>
  )
}
