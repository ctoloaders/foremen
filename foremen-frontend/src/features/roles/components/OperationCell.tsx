import { useMatrixStore } from '../stores/matrix-store'
import type { OperationDto } from '../types'

export interface OperationCellProps {
  roleId: number
  resourceId: number
  operations: OperationDto[]
  /** Server-state active operation IDs for this cell */
  activeOperationIds: Set<number>
}

/**
 * OperationCell — renders clickable operation letter buttons (C, R, U, D).
 * Active = primary color, Inactive = muted-foreground.
 * Click toggles the permission via matrixStore.
 */
export function OperationCell({
  roleId,
  resourceId,
  operations,
  activeOperationIds,
}: OperationCellProps) {
  const getLocalState = useMatrixStore((s) => s.getLocalState)
  const toggleOperation = useMatrixStore((s) => s.toggleOperation)

  return (
    <div className="flex items-center justify-center gap-1">
      {operations.map((op) => {
        // Local override takes precedence over server state
        const localOverride = getLocalState(roleId, resourceId, op.id)
        const isActive = localOverride !== undefined ? localOverride : activeOperationIds.has(op.id)

        return (
          <button
            key={op.id}
            type="button"
            onClick={() => toggleOperation(roleId, resourceId, op.id, isActive)}
            className={`inline-flex h-6 w-6 items-center justify-center rounded text-xs font-semibold transition-colors cursor-pointer hover:bg-muted ${
              isActive
                ? 'bg-primary/20 text-primary'
                : 'text-muted-foreground'
            }`}
            aria-label={`${op.code} ${isActive ? 'active' : 'inactive'}`}
            aria-pressed={isActive}
          >
            {op.code.charAt(0)}
          </button>
        )
      })}
    </div>
  )
}
