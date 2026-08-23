import { useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { Plus, Pencil, Trash2 } from 'lucide-react'

import { DataTable } from '@/components/data-table'
import type { ColumnConfig, FetchParams, PaginatedResponse as DTPaginatedResponse } from '@/components/data-table'
import { Button } from '@/components/ui/button'
import type { RoleDto } from '../types'

interface RolesListTabProps {
  onCreateRole: () => void
  onEditRole: (roleId: number) => void
  onDeleteRole: (role: RoleDto) => void
}

const columns: ColumnConfig<RoleDto>[] = [
  {
    field: 'code',
    headerKey: 'roles.table.code',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '100px',
  },
  {
    field: 'name',
    headerKey: 'roles.table.name',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '150px',
  },
  {
    field: 'description',
    headerKey: 'roles.table.description',
    dataType: 'string',
    sortable: false,
    filterable: true,
    searchable: true,
    minWidth: '200px',
  },
  {
    field: 'system',
    headerKey: 'roles.table.system',
    dataType: 'boolean',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '80px',
    render: (value) => {
      if (value === true) {
        return (
          <span className="inline-flex items-center rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
            ✓
          </span>
        )
      }
      return null
    },
  },
]

export function RolesListTab({
  onCreateRole,
  onEditRole,
  onDeleteRole,
}: RolesListTabProps) {
  const { t } = useTranslation()

  // Adapter: bridges DataTable's FetchParams to the roles API
  const fetchFn = useCallback(async (params: FetchParams): Promise<DTPaginatedResponse<RoleDto>> => {
    const searchParams = new URLSearchParams()
    searchParams.set('page', String(params.page))
    searchParams.set('size', String(params.size))
    if (params.query) searchParams.set('query', params.query)
    for (const sortEntry of params.sort) {
      searchParams.append('sort', sortEntry)
    }

    const locale = (() => {
      try { return localStorage.getItem('foremen-locale') || 'pl' } catch { return 'pl' }
    })()

    const response = await fetch(`/api/roles?${searchParams}`, {
      headers: { 'Accept-Language': locale },
    })
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    const data = await response.json()

    // Bridge: add first/last fields that DataTable expects
    return {
      ...data,
      first: data.number === 0,
      last: data.number >= data.totalPages - 1,
    }
  }, [])

  // Row actions: edit and delete buttons
  const rowActions = useCallback((role: RoleDto) => (
    <div className="flex items-center gap-1">
      <Button
        variant="ghost"
        size="icon"
        onClick={(e) => { e.stopPropagation(); onEditRole(role.id) }}
        aria-label={t('common.edit')}
      >
        <Pencil className="h-4 w-4" />
      </Button>
      {!role.system && (
        <Button
          variant="ghost"
          size="icon"
          onClick={(e) => { e.stopPropagation(); onDeleteRole(role) }}
          aria-label={t('common.delete')}
          className="text-destructive hover:text-destructive"
        >
          <Trash2 className="h-4 w-4" />
        </Button>
      )}
    </div>
  ), [onEditRole, onDeleteRole, t])

  return (
    <div className="space-y-4">
      {/* Create button above the table */}
      <div className="flex justify-end">
        <Button onClick={onCreateRole}>
          <Plus className="mr-2 h-4 w-4" />
          {t('roles.actions.create')}
        </Button>
      </div>

      {/* DataTable with full search, sort, filter, pagination */}
      <DataTable<RoleDto>
        entityKey="roles"
        columns={columns}
        fetchFn={fetchFn}
        defaultPageSize={10}
        pageSizeOptions={[10, 25, 50]}
        onRowClick={(role) => onEditRole(role.id)}
        rowActions={rowActions}
      />
    </div>
  )
}
