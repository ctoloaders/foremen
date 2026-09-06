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
import { fetchMaterialCategories } from '../api/material-categories-api'
import { ActiveBadge } from './ActiveBadge'
import type { MaterialCategoryDto } from '../types'

interface MaterialCategoriesListProps {
  onCreateMaterialCategory: () => void
  onEditMaterialCategory: (materialCategoryId: number) => void
  onDeleteMaterialCategory: (materialCategory: MaterialCategoryDto) => void
}

const columns: ColumnConfig<MaterialCategoryDto>[] = [
  {
    field: 'code',
    headerKey: 'materialCategories.table.code',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '100px',
  },
  {
    field: 'name',
    headerKey: 'materialCategories.table.name',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '200px',
  },
  {
    field: 'active',
    headerKey: 'materialCategories.table.active',
    dataType: 'boolean',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '100px',
    render: (value) => <ActiveBadge active={value === true} />,
  },
]

export function MaterialCategoriesList({
  onCreateMaterialCategory,
  onEditMaterialCategory,
  onDeleteMaterialCategory,
}: MaterialCategoriesListProps) {
  const { t } = useTranslation()
  const { hasPermission } = usePermission()

  // Permission gating for resource MATERIAL_CATEGORIES (FOR-03-07)
  const canCreate = hasPermission('MATERIAL_CATEGORIES', 'CREATE')
  const canUpdate = hasPermission('MATERIAL_CATEGORIES', 'UPDATE')
  const canDelete = hasPermission('MATERIAL_CATEGORIES', 'DELETE')

  // Adapter: bridges DataTable's FetchParams to the material-categories API (which
  // goes through the authenticated apiRequest client), then adds the first/last
  // fields DataTable expects.
  const fetchFn = useCallback(
    async (params: FetchParams): Promise<DTPaginatedResponse<MaterialCategoryDto>> => {
      const data = await fetchMaterialCategories({
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
  // Material categories are a plain dictionary: no system-delete guard — all rows
  // get delete when canDelete. Returns null when neither is permitted so DataTable
  // collapses the row-actions column.
  const rowActions = useCallback(
    (materialCategory: MaterialCategoryDto) => {
      if (!canUpdate && !canDelete) return null
      return (
        <div className="flex items-center gap-1">
          {canUpdate && (
            <Button
              variant="ghost"
              size="icon"
              onClick={(e) => {
                e.stopPropagation()
                onEditMaterialCategory(materialCategory.id)
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
                onDeleteMaterialCategory(materialCategory)
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
    [onEditMaterialCategory, onDeleteMaterialCategory, t, canUpdate, canDelete],
  )

  return (
    <div className="space-y-4">
      {/* Create button above the table — rendered only with CREATE */}
      {canCreate && (
        <div className="flex justify-end">
          <Button onClick={onCreateMaterialCategory}>
            <Plus className="mr-2 h-4 w-4" />
            {t('materialCategories.actions.create')}
          </Button>
        </div>
      )}

      {/* DataTable with full search, sort, filter, pagination */}
      <DataTable<MaterialCategoryDto>
        entityKey="material-categories"
        resource="MATERIAL_CATEGORIES"
        columns={columns}
        fetchFn={fetchFn}
        defaultPageSize={10}
        pageSizeOptions={[10, 25, 50]}
        onRowClick={canUpdate ? (materialCategory) => onEditMaterialCategory(materialCategory.id) : undefined}
        rowActions={rowActions}
      />
    </div>
  )
}
