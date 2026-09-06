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
import { fetchRoomTypes } from '../api/room-types-api'
import { ActiveBadge } from './ActiveBadge'
import type { RoomTypeDto } from '../types'

interface RoomTypesListProps {
  onCreateRoomType: () => void
  onEditRoomType: (roomTypeId: number) => void
  onDeleteRoomType: (roomType: RoomTypeDto) => void
}

const columns: ColumnConfig<RoomTypeDto>[] = [
  {
    field: 'code',
    headerKey: 'roomTypes.table.code',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '100px',
  },
  {
    field: 'name',
    headerKey: 'roomTypes.table.name',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '200px',
  },
  {
    field: 'active',
    headerKey: 'roomTypes.table.active',
    dataType: 'boolean',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '100px',
    render: (value) => <ActiveBadge active={value === true} />,
  },
]

export function RoomTypesList({
  onCreateRoomType,
  onEditRoomType,
  onDeleteRoomType,
}: RoomTypesListProps) {
  const { t } = useTranslation()
  const { hasPermission } = usePermission()

  // Permission gating for resource ROOM_TYPES (FOR-03-07)
  const canCreate = hasPermission('ROOM_TYPES', 'CREATE')
  const canUpdate = hasPermission('ROOM_TYPES', 'UPDATE')
  const canDelete = hasPermission('ROOM_TYPES', 'DELETE')

  // Adapter: bridges DataTable's FetchParams to the room-types API (which goes
  // through the authenticated apiRequest client), then adds the first/last
  // fields DataTable expects.
  const fetchFn = useCallback(
    async (params: FetchParams): Promise<DTPaginatedResponse<RoomTypeDto>> => {
      const data = await fetchRoomTypes({
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
  // Room types are a plain dictionary: no system-delete guard — all rows get
  // delete when canDelete. Returns null when neither is permitted so DataTable
  // collapses the row-actions column.
  const rowActions = useCallback(
    (roomType: RoomTypeDto) => {
      if (!canUpdate && !canDelete) return null
      return (
        <div className="flex items-center gap-1">
          {canUpdate && (
            <Button
              variant="ghost"
              size="icon"
              onClick={(e) => {
                e.stopPropagation()
                onEditRoomType(roomType.id)
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
                onDeleteRoomType(roomType)
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
    [onEditRoomType, onDeleteRoomType, t, canUpdate, canDelete],
  )

  return (
    <div className="space-y-4">
      {/* Create button above the table — rendered only with CREATE */}
      {canCreate && (
        <div className="flex justify-end">
          <Button onClick={onCreateRoomType}>
            <Plus className="mr-2 h-4 w-4" />
            {t('roomTypes.actions.create')}
          </Button>
        </div>
      )}

      {/* DataTable with full search, sort, filter, pagination */}
      <DataTable<RoomTypeDto>
        entityKey="room-types"
        resource="ROOM_TYPES"
        columns={columns}
        fetchFn={fetchFn}
        defaultPageSize={10}
        pageSizeOptions={[10, 25, 50]}
        onRowClick={canUpdate ? (roomType) => onEditRoomType(roomType.id) : undefined}
        rowActions={rowActions}
      />
    </div>
  )
}
