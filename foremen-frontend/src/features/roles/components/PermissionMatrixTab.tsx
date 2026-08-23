import { useState, useMemo, useEffect, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Search, ChevronLeft, ChevronRight, Loader2 } from 'lucide-react'

import { useRoles, useResources, useOperations, useMultipleRolePermissions } from '../api/query-hooks'
import { useBatchUpdatePermissions } from '../api/mutation-hooks'
import { useMatrixStore } from '../stores/matrix-store'
import { InlineError } from './InlineError'
import { MatrixSkeleton } from './MatrixSkeleton'
import { PermissionMatrix } from './PermissionMatrix'
import type {
  BatchRolePermissionRequest,
  PermissionEntryRequest,
  RolePermissionResponse,
  ResourceDto,
  OperationDto,
} from '../types'

const PAGE_SIZE = 10
const DEBOUNCE_MS = 300

/**
 * PermissionMatrixTab — Container for the Permission Matrix tab (Tab 2).
 *
 * Responsibilities:
 * - Search input that filters displayed roles by name (debounced 300ms)
 * - Unsaved changes indicator when matrixStore hasChanges
 * - "Save" button enabled only when hasChanges() is true
 * - Pagination for roles (page size 10)
 * - Fetches roles (paginated with search), resources (all), operations (all)
 * - Fetches permissions for all visible roles
 * - On save: builds BatchRolePermissionRequest, calls useBatchUpdatePermissions
 * - On save success: resets changes, shows success toast
 * - On save failure: shows error toast, preserves local changes
 * - Shows MatrixSkeleton while loading
 * - Shows inline error with retry button if queries fail
 *
 * Requirements: 6.1, 6.7, 6.8, 6.9, 6.10, 6.11, 6.12, 6.15, 6.16
 */
export function PermissionMatrixTab() {
  const { t } = useTranslation()

  // --- Local state ---
  const [page, setPage] = useState(0)
  const [searchInput, setSearchInput] = useState('')
  const [debouncedQuery, setDebouncedQuery] = useState('')

  // --- Zustand store ---
  const hasChanges = useMatrixStore((s) => s.hasChanges())
  const dirtyRoleIds = useMatrixStore((s) => s.dirtyRoleIds)
  const localChanges = useMatrixStore((s) => s.localChanges)
  const resetChanges = useMatrixStore((s) => s.resetChanges)

  // --- Debounce search input ---
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedQuery(searchInput)
      setPage(0)
    }, DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [searchInput])

  // --- Data fetching ---
  const queryParams = useMemo(
    () => ({ page, size: PAGE_SIZE, query: debouncedQuery }),
    [page, debouncedQuery],
  )

  const rolesQuery = useRoles(queryParams)
  const resourcesQuery = useResources()
  const operationsQuery = useOperations()

  const roles = rolesQuery.data?.content ?? []
  const totalPages = rolesQuery.data?.totalPages ?? 0
  const currentPage = rolesQuery.data?.number ?? 0

  const resources: ResourceDto[] = resourcesQuery.data?.content ?? []
  const operations: OperationDto[] = operationsQuery.data?.content ?? []

  // Fetch permissions for all visible roles
  const roleIds = useMemo(() => roles.map((r) => r.id), [roles])
  const permissionsQueries = useMultipleRolePermissions(roleIds)

  // Build permissions map: roleId → RolePermissionResponse
  const permissionsMap = useMemo(() => {
    const map = new Map<number, RolePermissionResponse>()
    permissionsQueries.forEach((q, idx) => {
      const roleId = roleIds[idx]
      if (q.data && roleId != null) {
        map.set(roleId, q.data)
      }
    })
    return map
  }, [permissionsQueries, roleIds])

  // --- Loading / Error states ---
  const isLoading =
    rolesQuery.isLoading ||
    resourcesQuery.isLoading ||
    operationsQuery.isLoading ||
    permissionsQueries.some((q) => q.isLoading)

  const isError =
    rolesQuery.isError ||
    resourcesQuery.isError ||
    operationsQuery.isError ||
    permissionsQueries.some((q) => q.isError)

  // --- Batch save mutation ---
  const batchMutation = useBatchUpdatePermissions()

  const handleSave = useCallback(() => {
    if (!hasChanges) return

    // Build the batch request from dirty roles
    const entries: BatchRolePermissionRequest['entries'] = []

    dirtyRoleIds.forEach((roleId) => {
      const roleLocalChanges = localChanges.get(roleId)
      const serverPermissions = permissionsMap.get(roleId)

      // Build permission entries for this role: for each resource, determine active operations
      const permissionEntries: PermissionEntryRequest[] = []

      resources.forEach((resource) => {
        // Get server-state active operation IDs for this resource
        const serverEntry = serverPermissions?.permissions.find(
          (p) => p.resourceId === resource.id,
        )
        const serverActiveOpIds = new Set(
          serverEntry?.operations.map((op) => op.operationId) ?? [],
        )

        // Apply local overrides
        const localCellState = roleLocalChanges?.get(resource.id)
        const activeOperationIds: number[] = []

        operations.forEach((op) => {
          const localOverride = localCellState?.[op.id]
          const isActive =
            localOverride !== undefined ? localOverride : serverActiveOpIds.has(op.id)
          if (isActive) {
            activeOperationIds.push(op.id)
          }
        })

        // Include this resource entry if it has any active operations or had any changes
        // We always include all resources for dirty roles to ensure full replacement
        permissionEntries.push({
          resourceId: resource.id,
          operationIds: activeOperationIds,
        })
      })

      entries.push({ roleId, permissions: permissionEntries })
    })

    const request: BatchRolePermissionRequest = { entries }

    batchMutation.mutate(request, {
      onSuccess: () => {
        resetChanges()
        toast.success(t('roles.toast.matrixSaveSuccess'))
      },
      onError: (error) => {
        const message = error instanceof Error ? error.message : t('roles.errors.network')
        toast.error(message)
      },
    })
  }, [
    hasChanges,
    dirtyRoleIds,
    localChanges,
    permissionsMap,
    resources,
    operations,
    batchMutation,
    resetChanges,
    t,
  ])

  // --- Retry handler ---
  const handleRetry = useCallback(() => {
    if (rolesQuery.isError) rolesQuery.refetch()
    if (resourcesQuery.isError) resourcesQuery.refetch()
    if (operationsQuery.isError) operationsQuery.refetch()
    permissionsQueries.forEach((q) => {
      if (q.isError) q.refetch()
    })
  }, [rolesQuery, resourcesQuery, operationsQuery, permissionsQueries])

  // --- Loading state ---
  if (isLoading) {
    return (
      <div className="space-y-4">
        <div className="flex items-center gap-3">
          <div className="h-9 flex-1 animate-pulse rounded-md bg-muted" />
          <div className="h-9 w-24 animate-pulse rounded-md bg-muted" />
        </div>
        <MatrixSkeleton />
      </div>
    )
  }

  // --- Error state ---
  if (isError) {
    return (
      <InlineError
        message={t('roles.errors.loadFailed')}
        onRetry={handleRetry}
      />
    )
  }

  return (
    <div className="space-y-4">
      {/* Toolbar: Search + Unsaved indicator + Save button */}
      <div className="flex items-center gap-3">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            placeholder={t('roles.matrix.search')}
            className="h-9 w-full rounded-md border border-border bg-background pl-9 pr-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>

        {hasChanges && (
          <span className="text-sm text-muted-foreground">
            {t('roles.matrix.unsavedChanges')}
          </span>
        )}

        <button
          type="button"
          onClick={handleSave}
          disabled={!hasChanges || batchMutation.isPending}
          className="inline-flex h-9 items-center gap-2 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:pointer-events-none disabled:opacity-50"
        >
          {batchMutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
          {t('roles.matrix.save')}
        </button>
      </div>

      {/* Permission Matrix Grid */}
      <PermissionMatrix
        roles={roles}
        resources={resources}
        operations={operations}
        permissions={permissionsMap}
      />

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between border-t border-border pt-4">
          <button
            type="button"
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={currentPage === 0}
            className="inline-flex h-8 items-center gap-1 rounded-md px-3 text-sm text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:pointer-events-none disabled:opacity-50"
          >
            <ChevronLeft className="h-4 w-4" />
            {t('roles.pagination.prev')}
          </button>

          <span className="text-sm text-muted-foreground">
            {t('roles.pagination.page', {
              current: currentPage + 1,
              total: totalPages,
            })}
          </span>

          <button
            type="button"
            onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
            disabled={currentPage >= totalPages - 1}
            className="inline-flex h-8 items-center gap-1 rounded-md px-3 text-sm text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:pointer-events-none disabled:opacity-50"
          >
            {t('roles.pagination.next')}
            <ChevronRight className="h-4 w-4" />
          </button>
        </div>
      )}
    </div>
  )
}
