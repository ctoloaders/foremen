import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import type { RoleDto, ResourceDto, OperationDto, RolePermissionResponse } from '../types'
import { OperationCell } from './OperationCell'

interface PermissionMatrixProps {
  roles: RoleDto[]
  resources: ResourceDto[]
  operations: OperationDto[]
  permissions: Map<number, RolePermissionResponse>
}

/**
 * PermissionMatrix grid — renders role names as rows and resource names as columns.
 * Each cell displays togglable operation letters (C, R, U, D) via OperationCell.
 *
 * Features:
 * - Sticky first column (role names) for narrow viewports
 * - Horizontal scroll for resource columns on viewport < 1024px
 * - All 4 operation letters visible in every cell at all viewport widths
 * - Uses shadcn/ui Table for the grid layout
 */
export function PermissionMatrix({
  roles,
  resources,
  operations,
  permissions,
}: PermissionMatrixProps) {
  /**
   * Compute active operation IDs for a given role + resource
   * from the server-side permissions data.
   */
  function getActiveOperationIds(roleId: number, resourceId: number): Set<number> {
    const rolePermissions = permissions.get(roleId)
    if (!rolePermissions) return new Set()

    const entry = rolePermissions.permissions.find((p) => p.resourceId === resourceId)
    if (!entry) return new Set()

    return new Set(entry.operations.map((op) => op.operationId))
  }

  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <Table className="min-w-max">
        <TableHeader>
          <TableRow>
            <TableHead className="sticky left-0 z-10 bg-background min-w-[160px]">
              Role
            </TableHead>
            {resources.map((resource) => (
              <TableHead key={resource.id} className="min-w-[120px] text-center">
                {resource.name}
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {roles.map((role) => (
            <TableRow key={role.id}>
              <TableCell className="sticky left-0 z-10 bg-background font-medium whitespace-nowrap">
                {role.name}
              </TableCell>
              {resources.map((resource) => {
                const activeOperationIds = getActiveOperationIds(role.id, resource.id)
                return (
                  <TableCell key={resource.id} className="text-center px-2">
                    <OperationCell
                      roleId={role.id}
                      resourceId={resource.id}
                      operations={operations}
                      activeOperationIds={activeOperationIds}
                    />
                  </TableCell>
                )
              })}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
