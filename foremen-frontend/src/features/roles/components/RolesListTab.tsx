import { useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { Plus, Pencil, Trash2 } from 'lucide-react'

import { DataTable } from '@/components/data-table'
import type { ColumnConfig, FetchParams, PaginatedResponse as DTPaginatedResponse } from '@/components/data-table'
import { Button } from '@/components/ui/button'
import { usePermission } from '@/hooks/usePermission'
import { fetchRoles } from '../api/roles-api'
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
  const { hasPermission } = usePermission()

  // Permission gating for resource ROLES (FOR-03-07)
  const canCreate = hasPermission('ROLES', 'CREATE')
  const canUpdate = hasPermission('ROLES', 'UPDATE')
  const canDelete = hasPermission('ROLES', 'DELETE')

  // Adapter: bridges DataTable's FetchParams to the roles API. Delegates to the
  // shared roles API (which goes through the authenticated apiRequest client),
  // then adds the first/last fields DataTable expects.
  const fetchFn = useCallback(async (params: FetchParams): Promise<DTPaginatedResponse<RoleDto>> => {
    const data = await fetchRoles({
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
  }, [])

  // Row actions: edit (UPDATE) and delete (DELETE) buttons, each gated by
  // permission. Returns null when neither is permitted so DataTable collapses
  // the row-actions column (FOR-03-07 Req 6.3, 6.4, 6.6).
  const rowActions = useCallback((role: RoleDto) => {
    if (!canUpdate && !canDelete) return null
    return (
      <div className="flex items-center gap-1">
        {canUpdate && (
          <Button
            variant="ghost"
            size="icon"
            onClick={(e) => { e.stopPropagation(); onEditRole(role.id) }}
            aria-label={t('common.edit')}
          >
            <Pencil className="h-4 w-4" />
          </Button>
        )}
        {canDelete && !role.system && (
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
    )
  }, [onEditRole, onDeleteRole, t, canUpdate, canDelete])

  return (
    <div className="space-y-4">
      {/* Create button above the table — rendered only with CREATE (Req 6.2) */}
      {canCreate && (
        <div className="flex justify-end">
          <Button onClick={onCreateRole}>
            <Plus className="mr-2 h-4 w-4" />
            {t('roles.actions.create')}
          </Button>
        </div>
      )}

      {/* DataTable with full search, sort, filter, pagination */}
      <DataTable<RoleDto>
        entityKey="roles"
        resource="ROLES"
        columns={columns}
        fetchFn={fetchFn}
        defaultPageSize={10}
        pageSizeOptions={[10, 25, 50]}
        onRowClick={canUpdate ? (role) => onEditRole(role.id) : undefined}
        rowActions={rowActions}
      />
    </div>
  )
}
