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
import { fetchMeasurementUnits } from '../api/measurement-units-api'
import { ActiveBadge } from './ActiveBadge'
import type { MeasurementUnitDto } from '../types'

interface MeasurementUnitsListProps {
  onCreateUnit: () => void
  onEditUnit: (unitId: number) => void
  onDeleteUnit: (unit: MeasurementUnitDto) => void
}

const columns: ColumnConfig<MeasurementUnitDto>[] = [
  {
    field: 'code',
    headerKey: 'measurementUnits.table.code',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '100px',
  },
  {
    field: 'name',
    headerKey: 'measurementUnits.table.name',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '200px',
  },
  {
    field: 'active',
    headerKey: 'measurementUnits.table.active',
    dataType: 'boolean',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '100px',
    render: (value) => <ActiveBadge active={value === true} />,
  },
]

export function MeasurementUnitsList({
  onCreateUnit,
  onEditUnit,
  onDeleteUnit,
}: MeasurementUnitsListProps) {
  const { t } = useTranslation()
  const { hasPermission } = usePermission()

  // Permission gating for resource MEASUREMENT_UNITS (FOR-03-07)
  const canCreate = hasPermission('MEASUREMENT_UNITS', 'CREATE')
  const canUpdate = hasPermission('MEASUREMENT_UNITS', 'UPDATE')
  const canDelete = hasPermission('MEASUREMENT_UNITS', 'DELETE')

  // Adapter: bridges DataTable's FetchParams to the units API (which goes
  // through the authenticated apiRequest client), then adds the first/last
  // fields DataTable expects.
  const fetchFn = useCallback(
    async (params: FetchParams): Promise<DTPaginatedResponse<MeasurementUnitDto>> => {
      const data = await fetchMeasurementUnits({
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
  // Units are a plain dictionary: no system-delete guard — all rows get delete
  // when canDelete. Returns null when neither is permitted so DataTable
  // collapses the row-actions column.
  const rowActions = useCallback(
    (unit: MeasurementUnitDto) => {
      if (!canUpdate && !canDelete) return null
      return (
        <div className="flex items-center gap-1">
          {canUpdate && (
            <Button
              variant="ghost"
              size="icon"
              onClick={(e) => {
                e.stopPropagation()
                onEditUnit(unit.id)
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
                onDeleteUnit(unit)
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
    [onEditUnit, onDeleteUnit, t, canUpdate, canDelete],
  )

  return (
    <div className="space-y-4">
      {/* Create button above the table — rendered only with CREATE */}
      {canCreate && (
        <div className="flex justify-end">
          <Button onClick={onCreateUnit}>
            <Plus className="mr-2 h-4 w-4" />
            {t('measurementUnits.actions.create')}
          </Button>
        </div>
      )}

      {/* DataTable with full search, sort, filter, pagination */}
      <DataTable<MeasurementUnitDto>
        entityKey="measurement-units"
        resource="MEASUREMENT_UNITS"
        columns={columns}
        fetchFn={fetchFn}
        defaultPageSize={10}
        pageSizeOptions={[10, 25, 50]}
        onRowClick={canUpdate ? (unit) => onEditUnit(unit.id) : undefined}
        rowActions={rowActions}
      />
    </div>
  )
}
