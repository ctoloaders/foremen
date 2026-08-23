import { create } from 'zustand'
import type { MatrixCellState } from '../types'

interface MatrixState {
  /** Local overrides: roleId → resourceId → { operationId: boolean } */
  localChanges: Map<number, Map<number, MatrixCellState>>
  /** Set of role IDs that have pending changes */
  dirtyRoleIds: Set<number>
  /** Toggle a single operation for a role+resource cell */
  toggleOperation: (roleId: number, resourceId: number, operationId: number, currentActive: boolean) => void
  /** Reset all local changes (after successful batch save) */
  resetChanges: () => void
  /** Check if a specific operation has been locally toggled */
  getLocalState: (roleId: number, resourceId: number, operationId: number) => boolean | undefined
  /** Whether there are any unsaved changes */
  hasChanges: () => boolean
}

export const useMatrixStore = create<MatrixState>((set, get) => ({
  localChanges: new Map(),
  dirtyRoleIds: new Set(),

  toggleOperation: (roleId, resourceId, operationId, currentActive) => {
    set((state) => {
      const newChanges = new Map(state.localChanges)
      if (!newChanges.has(roleId)) {
        newChanges.set(roleId, new Map())
      }
      const roleMap = newChanges.get(roleId)!
      if (!roleMap.has(resourceId)) {
        roleMap.set(resourceId, {})
      }
      const cellState = { ...roleMap.get(resourceId)! }

      if (cellState[operationId] === undefined) {
        // First toggle: flip from server state
        cellState[operationId] = !currentActive
      } else {
        if (cellState[operationId] === currentActive) {
          // Toggling back to original — flip again
          cellState[operationId] = !currentActive
        } else {
          // Toggling back to server state — remove the override
          delete cellState[operationId]
        }
      }

      roleMap.set(resourceId, cellState)

      // Update dirty set
      const newDirty = new Set(state.dirtyRoleIds)
      const hasRoleChanges = Array.from(roleMap.values()).some(
        (cell) => Object.keys(cell).length > 0,
      )
      if (hasRoleChanges) {
        newDirty.add(roleId)
      } else {
        newDirty.delete(roleId)
        newChanges.delete(roleId)
      }

      return { localChanges: newChanges, dirtyRoleIds: newDirty }
    })
  },

  resetChanges: () => {
    set({ localChanges: new Map(), dirtyRoleIds: new Set() })
  },

  getLocalState: (roleId, resourceId, operationId) => {
    const roleMap = get().localChanges.get(roleId)
    if (!roleMap) return undefined
    const cellState = roleMap.get(resourceId)
    if (!cellState) return undefined
    return cellState[operationId]
  },

  hasChanges: () => get().dirtyRoleIds.size > 0,
}))
