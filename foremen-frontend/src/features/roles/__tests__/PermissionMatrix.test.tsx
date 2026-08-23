import { render, screen } from '@testing-library/react'
import { describe, it, expect, beforeEach } from 'vitest'
import type { RoleDto, ResourceDto, OperationDto, RolePermissionResponse } from '../types'
import { useMatrixStore } from '../stores/matrix-store'

import { PermissionMatrix } from '../components/PermissionMatrix'

// --- Test data ---

const mockRoles: RoleDto[] = [
  {
    id: 1,
    code: 'ADMIN',
    name: 'Administrator',
    description: null,
    system: true,
  },
  {
    id: 2,
    code: 'CLIENT',
    name: 'Klient',
    description: null,
    system: false,
  },
]

const mockResources: ResourceDto[] = [
  { id: 10, code: 'PROJECTS', name: 'Projekty', description: null },
  { id: 20, code: 'USERS', name: 'Użytkownicy', description: null },
]

const mockOperations: OperationDto[] = [
  { id: 100, code: 'CREATE', name: 'Create' },
  { id: 200, code: 'READ', name: 'Read' },
  { id: 300, code: 'UPDATE', name: 'Update' },
  { id: 400, code: 'DELETE', name: 'Delete' },
]

const mockPermissions: Map<number, RolePermissionResponse> = new Map([
  [
    1,
    {
      roleId: 1,
      permissions: [
        {
          resourceId: 10,
          resourceCode: 'PROJECTS',
          resourceName: 'Projekty',
          operations: [
            { operationId: 100, operationCode: 'CREATE', operationName: 'Create' },
            { operationId: 200, operationCode: 'READ', operationName: 'Read' },
          ],
        },
        {
          resourceId: 20,
          resourceCode: 'USERS',
          resourceName: 'Użytkownicy',
          operations: [
            { operationId: 200, operationCode: 'READ', operationName: 'Read' },
          ],
        },
      ],
    },
  ],
  [
    2,
    {
      roleId: 2,
      permissions: [
        {
          resourceId: 10,
          resourceCode: 'PROJECTS',
          resourceName: 'Projekty',
          operations: [
            { operationId: 200, operationCode: 'READ', operationName: 'Read' },
          ],
        },
      ],
    },
  ],
])

describe('PermissionMatrix', () => {
  beforeEach(() => {
    useMatrixStore.getState().resetChanges()
  })

  it('renders grid with role rows and resource columns', () => {
    render(
      <PermissionMatrix
        roles={mockRoles}
        resources={mockResources}
        operations={mockOperations}
        permissions={mockPermissions}
      />,
    )

    // Header row should have resource names
    expect(screen.getByText('Projekty')).toBeInTheDocument()
    expect(screen.getByText('Użytkownicy')).toBeInTheDocument()

    // Role names displayed (PL locale since we mock 'pl')
    expect(screen.getByText('Administrator')).toBeInTheDocument()
    expect(screen.getByText('Klient')).toBeInTheDocument()
  })

  it('operation letters show correct active/inactive state from server data', () => {
    render(
      <PermissionMatrix
        roles={mockRoles}
        resources={mockResources}
        operations={mockOperations}
        permissions={mockPermissions}
      />,
    )

    // For role 1 (ADMIN) + resource 10 (PROJECTS): active operations are CREATE (100) and READ (200)
    // We get all operation letter spans — each cell renders 4 letters (C, R, U, D)
    // Role 1 has 2 resources = 2 cells × 4 letters = 8 operation letters
    // Role 2 has 2 resources = 2 cells × 4 letters = 8 operation letters
    // Total: 16 spans with operation letters

    const allSpans = screen.getAllByText(/^[CRUD]$/)
    expect(allSpans.length).toBe(16) // 2 roles × 2 resources × 4 operations

    // Check that active operations have 'text-primary' class
    // and inactive have 'text-muted-foreground' class
    const primarySpans = allSpans.filter((span) =>
      span.className.includes('text-primary'),
    )
    const mutedSpans = allSpans.filter((span) =>
      span.className.includes('text-muted-foreground'),
    )

    // Active operations:
    // Role 1 + Resource 10: CREATE, READ (2 active)
    // Role 1 + Resource 20: READ (1 active)
    // Role 2 + Resource 10: READ (1 active)
    // Role 2 + Resource 20: (none active)
    // Total active = 4, inactive = 12
    expect(primarySpans.length).toBe(4)
    expect(mutedSpans.length).toBe(12)
  })

  it('active operations styled differently from inactive operations (checking class names)', () => {
    render(
      <PermissionMatrix
        roles={mockRoles}
        resources={mockResources}
        operations={mockOperations}
        permissions={mockPermissions}
      />,
    )

    const allSpans = screen.getAllByText(/^[CRUD]$/)

    // Every span should have either text-primary or text-muted-foreground
    allSpans.forEach((span) => {
      const hasPrimary = span.className.includes('text-primary')
      const hasMuted = span.className.includes('text-muted-foreground')
      expect(hasPrimary || hasMuted).toBe(true)
      // And they should be mutually exclusive
      expect(hasPrimary && hasMuted).toBe(false)
    })
  })

  it('clicking an operation letter toggles local state via matrixStore', async () => {
    // OperationCell currently renders static spans (click handler is added in task 7.3).
    // This test verifies that matrixStore.toggleOperation works correctly when called
    // for a given role+resource+operation combination, which is the backing logic
    // for the click interaction.
    const store = useMatrixStore.getState()

    // Simulate what a click on "CREATE" for role 1, resource 10 would do
    // (operation 100 is currently active for this cell)
    store.toggleOperation(1, 10, 100, true)

    const state = useMatrixStore.getState()
    // Should toggle to inactive (false)
    expect(state.getLocalState(1, 10, 100)).toBe(false)
    expect(state.dirtyRoleIds.has(1)).toBe(true)
  })

  it('role names displayed from pre-resolved name field', () => {
    render(
      <PermissionMatrix
        roles={mockRoles}
        resources={mockResources}
        operations={mockOperations}
        permissions={mockPermissions}
      />,
    )

    expect(screen.getByText('Administrator')).toBeInTheDocument()
    expect(screen.getByText('Klient')).toBeInTheDocument()
  })
})
