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
import { fetchWorkCategories } from '../api/work-categories-api'
import { ActiveBadge } from './ActiveBadge'
import type { WorkCategoryDto } from '../types'

interface WorkCategoriesListProps {
  onCreateCategory: () => void
  onEditCategory: (categoryId: number) => void
  onDeleteCategory: (category: WorkCategoryDto) => void
}

const columns: ColumnConfig<WorkCategoryDto>[] = [
  {
    field: 'orderNo',
    headerKey: 'workCategories.table.orderNo',
    dataType: 'number',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '80px',
  },
  {
    field: 'code',
    headerKey: 'workCategories.table.code',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '140px',
  },
  {
    field: 'name',
    headerKey: 'workCategories.table.name',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '240px',
  },
  {
    field: 'active',
    headerKey: 'workCategories.table.active',
    dataType: 'boolean',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '100px',
    render: (value) => <ActiveBadge active={value === true} />,
  },
]

// Default sort: order categories by their orderNo ascending.
const defaultSort: SortState[] = [{ field: 'orderNo', direction: 'asc', priority: 0 }]

export function WorkCategoriesList({
  onCreateCategory,
  onEditCategory,
  onDeleteCategory,
}: WorkCategoriesListProps) {
  const { t } = useTranslation()
  const { hasPermission } = usePermission()

  // Permission gating for resource WORK_CATEGORIES (FOR-03-07)
  const canCreate = hasPermission('WORK_CATEGORIES', 'CREATE')
  const canUpdate = hasPermission('WORK_CATEGORIES', 'UPDATE')
  const canDelete = hasPermission('WORK_CATEGORIES', 'DELETE')

  // Adapter: bridges DataTable's FetchParams to the work-categories API (which
  // goes through the authenticated apiRequest client), then adds the first/last
  // fields DataTable expects.
  const fetchFn = useCallback(
    async (params: FetchParams): Promise<DTPaginatedResponse<WorkCategoryDto>> => {
      const data = await fetchWorkCategories({
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
  // Work categories are a plain dictionary: no system-delete guard — all rows
  // get delete when canDelete. Returns null when neither is permitted so
  // DataTable collapses the row-actions column.
  const rowActions = useCallback(
    (category: WorkCategoryDto) => {
      if (!canUpdate && !canDelete) return null
      return (
        <div className="flex items-center gap-1">
          {canUpdate && (
            <Button
              variant="ghost"
              size="icon"
              onClick={(e) => {
                e.stopPropagation()
                onEditCategory(category.id)
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
                onDeleteCategory(category)
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
    [onEditCategory, onDeleteCategory, t, canUpdate, canDelete],
  )

  return (
    <div className="space-y-4">
      {/* Create button above the table — rendered only with CREATE */}
      {canCreate && (
        <div className="flex justify-end">
          <Button onClick={onCreateCategory}>
            <Plus className="mr-2 h-4 w-4" />
            {t('workCategories.actions.create')}
          </Button>
        </div>
      )}

      {/* DataTable with full search, sort, filter, pagination */}
      <DataTable<WorkCategoryDto>
        entityKey="work-categories"
        resource="WORK_CATEGORIES"
        columns={columns}
        fetchFn={fetchFn}
        defaultPageSize={10}
        pageSizeOptions={[10, 25, 50]}
        defaultSort={defaultSort}
        onRowClick={canUpdate ? (category) => onEditCategory(category.id) : undefined}
        rowActions={rowActions}
      />
    </div>
  )
}
