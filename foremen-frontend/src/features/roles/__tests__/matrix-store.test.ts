import { describe, it, expect, beforeEach } from 'vitest'
import { useMatrixStore } from '../stores/matrix-store'

describe('useMatrixStore', () => {
  beforeEach(() => {
    // Reset store state between tests
    useMatrixStore.getState().resetChanges()
  })

  describe('toggleOperation', () => {
    it('first toggle flips from server state and adds to dirtyRoleIds', () => {
      const { toggleOperation } = useMatrixStore.getState()

      // Server state: operation 1 is currently active (true)
      toggleOperation(1, 10, 100, true)

      const state = useMatrixStore.getState()
      // Should flip to false (opposite of currentActive=true)
      expect(state.localChanges.get(1)?.get(10)?.[100]).toBe(false)
      expect(state.dirtyRoleIds.has(1)).toBe(true)
    })

    it('toggling back to original state removes override and from dirtyRoleIds', () => {
      const store = useMatrixStore.getState()

      // First toggle: server state is true → local becomes false
      store.toggleOperation(1, 10, 100, true)
      expect(useMatrixStore.getState().localChanges.get(1)?.get(10)?.[100]).toBe(false)
      expect(useMatrixStore.getState().dirtyRoleIds.has(1)).toBe(true)

      // Second toggle: local is false, currentActive is true → toggling back to server state removes override
      useMatrixStore.getState().toggleOperation(1, 10, 100, true)

      const state = useMatrixStore.getState()
      // Override should be removed (role has no more changes)
      expect(state.localChanges.has(1)).toBe(false)
      expect(state.dirtyRoleIds.has(1)).toBe(false)
    })

    it('multiple toggles on different operations in same cell accumulate', () => {
      const store = useMatrixStore.getState()

      // Toggle operation 100 (currently active) → becomes false
      store.toggleOperation(1, 10, 100, true)
      // Toggle operation 200 (currently inactive) → becomes true
      useMatrixStore.getState().toggleOperation(1, 10, 200, false)
      // Toggle operation 300 (currently active) → becomes false
      useMatrixStore.getState().toggleOperation(1, 10, 300, true)

      const state = useMatrixStore.getState()
      const cellState = state.localChanges.get(1)?.get(10)

      expect(cellState?.[100]).toBe(false)
      expect(cellState?.[200]).toBe(true)
      expect(cellState?.[300]).toBe(false)
      expect(state.dirtyRoleIds.has(1)).toBe(true)
    })
  })

  describe('resetChanges', () => {
    it('clears localChanges and dirtyRoleIds', () => {
      const store = useMatrixStore.getState()

      // Make some changes
      store.toggleOperation(1, 10, 100, true)
      useMatrixStore.getState().toggleOperation(2, 20, 200, false)

      // Verify changes exist
      expect(useMatrixStore.getState().dirtyRoleIds.size).toBe(2)
      expect(useMatrixStore.getState().localChanges.size).toBe(2)

      // Reset
      useMatrixStore.getState().resetChanges()

      const state = useMatrixStore.getState()
      expect(state.localChanges.size).toBe(0)
      expect(state.dirtyRoleIds.size).toBe(0)
    })
  })

  describe('hasChanges', () => {
    it('returns false initially', () => {
      expect(useMatrixStore.getState().hasChanges()).toBe(false)
    })

    it('returns true after toggle', () => {
      useMatrixStore.getState().toggleOperation(1, 10, 100, true)
      expect(useMatrixStore.getState().hasChanges()).toBe(true)
    })

    it('returns false after reset', () => {
      useMatrixStore.getState().toggleOperation(1, 10, 100, true)
      expect(useMatrixStore.getState().hasChanges()).toBe(true)

      useMatrixStore.getState().resetChanges()
      expect(useMatrixStore.getState().hasChanges()).toBe(false)
    })
  })

  describe('getLocalState', () => {
    it('returns undefined when no override exists', () => {
      const result = useMatrixStore.getState().getLocalState(1, 10, 100)
      expect(result).toBeUndefined()
    })

    it('returns the toggled boolean when override exists', () => {
      // Toggle operation 100 (currently active=true) → local becomes false
      useMatrixStore.getState().toggleOperation(1, 10, 100, true)
      expect(useMatrixStore.getState().getLocalState(1, 10, 100)).toBe(false)

      // Toggle operation 200 (currently inactive=false) → local becomes true
      useMatrixStore.getState().toggleOperation(1, 10, 200, false)
      expect(useMatrixStore.getState().getLocalState(1, 10, 200)).toBe(true)
    })
  })
})
