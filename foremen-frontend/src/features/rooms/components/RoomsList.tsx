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
import { fetchRooms } from '../api/rooms-api'
import { MeasureValueCell } from './MeasureValueCell'
import type { RoomDto } from '../types'

interface RoomsListProps {
  onCreateRoom: () => void
  onEditRoom: (roomId: number) => void
  onDeleteRoom: (room: RoomDto) => void
}

// Columns. The `project` and `roomType` columns carry an inline `reference`
// descriptor (mirroring the backend MetadataResponse.ReferenceInfo) so the
// DataTable renders a ReferenceFilter for each, sourcing options from the
// referenced endpoints. The render fns show the server-resolved display values
// (projectName / roomTypeName). The floorArea/wallArea/perimeter columns render
// their `{ value, source }` MeasureValue via MeasureValueCell (number + source
// badge).
const columns: ColumnConfig<RoomDto>[] = [
  {
    field: 'project',
    headerKey: 'rooms.table.project',
    dataType: 'string',
    sortable: false,
    filterable: true,
    searchable: false,
    minWidth: '200px',
    reference: {
      targetResource: 'projects',
      optionsPath: '/api/projects',
      labelField: 'name',
      labelI18n: false,
      idPath: 'project.id',
    },
    render: (_value, row) => row.projectName,
  },
  {
    field: 'roomType',
    headerKey: 'rooms.table.roomType',
    dataType: 'string',
    sortable: false,
    filterable: true,
    searchable: false,
    minWidth: '180px',
    reference: {
      targetResource: 'room-types',
      optionsPath: '/api/room-types',
      labelField: 'name',
      labelI18n: true,
      idPath: 'roomType.id',
    },
    render: (_value, row) => row.roomTypeName,
  },
  {
    field: 'label',
    headerKey: 'rooms.table.label',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '160px',
    render: (value) => (value ? String(value) : '—'),
  },
  {
    field: 'floorArea',
    headerKey: 'rooms.table.floorArea',
    dataType: 'number',
    sortable: false,
    filterable: false,
    searchable: false,
    minWidth: '160px',
    render: (_value, row) => <MeasureValueCell measure={row.floorArea} />,
  },
  {
    field: 'wallArea',
    headerKey: 'rooms.table.wallArea',
    dataType: 'number',
    sortable: false,
    filterable: false,
    searchable: false,
    minWidth: '160px',
    render: (_value, row) => <MeasureValueCell measure={row.wallArea} />,
  },
  {
    field: 'perimeter',
    headerKey: 'rooms.table.perimeter',
    dataType: 'number',
    sortable: false,
    filterable: false,
    searchable: false,
    minWidth: '160px',
    render: (_value, row) => <MeasureValueCell measure={row.perimeter} />,
  },
  {
    field: 'ceilingHeight',
    headerKey: 'rooms.table.ceilingHeight',
    dataType: 'number',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '140px',
    render: (value) => (value != null ? String(value) : '—'),
  },
]

// Default sort: by label ascending.
const defaultSort: SortState[] = [{ field: 'label', direction: 'asc', priority: 0 }]

export function RoomsList({ onCreateRoom, onEditRoom, onDeleteRoom }: Readonly<RoomsListProps>) {
  const { t } = useTranslation()
  const { hasPermission } = usePermission()

  // Permission gating for resource ROOMS (FOR-04-14). FOREMAN has READ/UPDATE but
  // NOT CREATE/DELETE, so those controls are hidden for FOREMAN.
  const canCreate = hasPermission('ROOMS', 'CREATE')
  const canUpdate = hasPermission('ROOMS', 'UPDATE')
  const canDelete = hasPermission('ROOMS', 'DELETE')

  // Adapter: bridges DataTable's FetchParams to the rooms API (which goes through
  // the authenticated apiRequest client), then adds the first/last fields
  // DataTable expects.
  const fetchFn = useCallback(
    async (params: FetchParams): Promise<DTPaginatedResponse<RoomDto>> => {
      const data = await fetchRooms({
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
    (room: RoomDto) => {
      if (!canUpdate && !canDelete) return null
      return (
        <div className="flex items-center gap-1">
          {canUpdate && (
            <Button
              variant="ghost"
              size="icon"
              onClick={(e) => {
                e.stopPropagation()
                onEditRoom(room.id)
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
                onDeleteRoom(room)
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
    [onEditRoom, onDeleteRoom, t, canUpdate, canDelete],
  )

  return (
    <div className="space-y-4">
      {canCreate && (
        <div className="flex justify-end">
          <Button onClick={onCreateRoom}>
            <Plus className="mr-2 h-4 w-4" />
            {t('rooms.actions.create')}
          </Button>
        </div>
      )}

      <DataTable<RoomDto>
        entityKey="rooms"
        resource="ROOMS"
        columns={columns}
        fetchFn={fetchFn}
        defaultPageSize={10}
        pageSizeOptions={[10, 25, 50]}
        defaultSort={defaultSort}
        onRowClick={canUpdate ? (room) => onEditRoom(room.id) : undefined}
        rowActions={rowActions}
      />
    </div>
  )
}
