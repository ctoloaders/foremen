# Implementation Plan: Roles UI (FOR-02-06-roles-ui)

## Overview

Implement the Roles management page for the Foremen admin panel. This includes a tabbed interface with CRUD for roles (Tab 1) and a permission matrix (Tab 2), plus a backend batch permission endpoint. The feature lives at `src/features/roles/` following established project conventions. Tech stack: React 19, TypeScript 5, TanStack Query v5, Zustand, React Hook Form + Zod, shadcn/ui, Tailwind CSS 4, i18next (PL/RU).

## Tasks

- [x] 1. Backend: Add batch permission endpoint
  - [x] 1.1 Create batch request/response records and add batch endpoint to RoleController
    - Create `BatchRolePermissionEntry` record in `com.foremen.controller.model` with fields `Long roleId` and `List<PermissionEntryRequest> permissions`
    - Create `BatchRolePermissionRequest` record with field `List<BatchRolePermissionEntry> entries`
    - Create `BatchRolePermissionResponse` record with field `List<RolePermissionResponse> results`
    - Add `batchReplacePermissions` method to `RoleService` that iterates entries and calls `replacePermissions` for each (runs in single `@Transactional`)
    - Add `PUT /api/roles/permissions/batch` endpoint to `RoleController` accepting `@Valid @RequestBody BatchRolePermissionRequest`
    - _Requirements: 6.8, 6.9, 6.10_

- [x] 2. Feature module setup: types, schemas, API layer
  - [x] 2.1 Create feature module directory structure and TypeScript interfaces
    - Create `src/features/roles/` directory with sub-folders: `components/`, `api/`, `stores/`, `schemas/`, `types/`
    - Create `src/features/roles/types/index.ts` with all TypeScript interfaces: `PaginatedResponse<T>`, `RoleExtendedDto`, `ResourceDto`, `OperationDto`, `PermissionEntry`, `OperationInfo`, `RolePermissionResponse`, `BatchRolePermissionEntry`, `BatchRolePermissionRequest`, `BatchRolePermissionResponse`, `RoleCreateRequest`, `RoleUpdateRequest`, `PermissionEntryRequest`, `RolePermissionRequest`, `RoleFormMode`, `MatrixCellState`, `MatrixLocalState`, `DirtyRoleIds`
    - Create `src/features/roles/index.ts` barrel export
    - _Requirements: 9.1, 9.2_

  - [x] 2.2 Implement Zod validation schemas
    - Create `src/features/roles/schemas/role-schema.ts` with `roleCreateSchema` and `roleUpdateSchema`
    - `roleCreateSchema`: code (required, regex `^[A-Z][A-Z0-9_]{1,49}$`), nameRU (min 2, max 100), namePL (min 2, max 100), descriptionRU (optional, max 500), descriptionPL (optional, max 500), system (boolean, default false)
    - `roleUpdateSchema`: same as create but omitting `code` field
    - Export inferred types `RoleCreateFormValues` and `RoleUpdateFormValues`
    - _Requirements: 10.1, 10.2_

  - [x] 2.3 Implement API client layer
    - Create `src/features/roles/api/roles-api.ts` with functions: `fetchRoles` (GET `/api/roles/extended`), `fetchRole` (GET `/api/roles/{id}`), `createRole` (POST `/api/roles`), `updateRole` (PUT `/api/roles/{id}`), `deleteRole` (DELETE `/api/roles/{id}`), `fetchResources` (GET `/api/resources`), `fetchOperations` (GET `/api/operations`), `fetchRolePermissions` (GET `/api/roles/{id}/permissions`), `batchUpdatePermissions` (PUT `/api/roles/permissions/batch`)
    - Implement `ApiError` class with status and message
    - Implement shared `handleResponse<T>` function for error parsing
    - _Requirements: 9.1, 9.2, 11.1, 11.2_

  - [x] 2.4 Implement TanStack Query hooks
    - Create `src/features/roles/api/query-hooks.ts` with `roleKeys` factory object and hooks: `useRoles`, `useRole`, `useResources`, `useOperations`, `useRolePermissions`, `useMultipleRolePermissions`
    - Configure staleTime: 30s for lists, 60s for details
    - Configure retry: 2 retries with exponential backoff
    - _Requirements: 9.1, 9.4, 9.5, 9.6_

  - [x] 2.5 Implement TanStack Mutation hooks
    - Create `src/features/roles/api/mutation-hooks.ts` with hooks: `useCreateRole`, `useUpdateRole`, `useDeleteRole`, `useBatchUpdatePermissions`
    - Each mutation invalidates related query caches on success
    - `useBatchUpdatePermissions` invalidates permission caches for each role in the batch result
    - _Requirements: 9.2, 9.3_

- [x] 3. Checkpoint - Verify backend compiles and API layer types are consistent
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Matrix Zustand store
  - [x] 4.1 Implement matrix dirty state store
    - Create `src/features/roles/stores/matrix-store.ts` with Zustand store
    - Implement `toggleOperation(roleId, resourceId, operationId, currentActive)` — toggles operation, adds to dirtyRoleIds; toggling back to server state removes the override
    - Implement `resetChanges()` — clears all localChanges and dirtyRoleIds
    - Implement `getLocalState(roleId, resourceId, operationId)` — returns local override or undefined
    - Implement `hasChanges()` — returns boolean
    - _Requirements: 6.5, 6.6, 6.15_

  - [x] 4.2 Write unit tests for matrix store
    - Test toggleOperation adds to localChanges and dirtyRoleIds
    - Test toggle back to original removes override
    - Test resetChanges clears state
    - Test hasChanges returns correct boolean
    - Test getLocalState returns undefined when no override
    - _Requirements: 6.5, 6.6_

- [x] 5. Roles List Tab (Tab 1) components
  - [x] 5.1 Implement RolesPage with tabbed layout
    - Create `src/features/roles/RolesPage.tsx` — top-level page component using shadcn/ui Tabs
    - Implement controlled tab state (default: 'list'), tab labels via i18n keys `roles.tabs.list` and `roles.tabs.matrix`
    - Manage form sheet state (`open`, `mode`, `roleId`) and delete dialog state (`open`, `role`)
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 16.5_

  - [x] 5.2 Implement RolesListTab with table and cards
    - Create `src/features/roles/components/RolesListTab.tsx` — container with search input, "Create Role" button, table/cards, and pagination
    - Create `src/features/roles/components/RolesTable.tsx` — desktop table using shadcn/ui Table showing: code, localized name, description, system badge, action buttons (edit, delete)
    - Create `src/features/roles/components/RolesCards.tsx` — mobile card layout (viewport < 768px)
    - Implement search input with debounced API query filtering
    - Implement server-side pagination (page size 10)
    - Display system badge (shadcn Badge, "secondary" variant) for `system=true` roles
    - Disable delete button for system roles with tooltip
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.6, 1.7, 1.8, 7.1, 7.2, 7.3, 12.1, 12.2, 16.1, 16.6, 16.8_

  - [x] 5.3 Implement skeleton loading states
    - Create `src/features/roles/components/RolesListSkeleton.tsx` — 5 skeleton table rows (desktop) or 5 skeleton cards (mobile) with CSS pulse animation (1.5–2s cycle)
    - Create `src/features/roles/components/RoleFormSkeleton.tsx` — skeleton placeholders for form fields
    - Create `src/features/roles/components/MatrixSkeleton.tsx` — skeleton grid with placeholder cells
    - _Requirements: 1.5, 13.1, 13.2, 13.3, 13.4_

  - [x] 5.4 Implement RoleFormSheet (create/edit)
    - Create `src/features/roles/components/RoleFormSheet.tsx` — sheet overlay using shadcn Sheet component
    - Use React Hook Form with `@hookform/resolvers/zod` and `roleCreateSchema`/`roleUpdateSchema`
    - Create mode: all fields empty, code editable
    - Edit mode: pre-populate from `useRole(id)`, code field disabled (immutable), system checkbox disabled for existing system roles
    - Validate on blur (individual fields) and on submit (entire form)
    - Display inline error messages below invalid fields (localized via i18n)
    - Disable submit button while mutation in progress
    - On success: close sheet, show success toast, invalidate caches
    - On error: show error toast, keep sheet open
    - Cancel button closes sheet without saving
    - Form fields: single column on mobile, two columns (nameRU/namePL side-by-side) on desktop
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 7.4, 10.1, 10.2, 10.3, 10.4, 10.5, 12.3, 16.2_

  - [x] 5.5 Implement DeleteRoleDialog
    - Create `src/features/roles/components/DeleteRoleDialog.tsx` — shadcn AlertDialog with localized title, description (includes role name), and action buttons
    - On confirm: call `useDeleteRole` mutation
    - On success: close dialog, success toast, invalidate caches
    - On HTTP 403: error toast indicating system roles cannot be deleted
    - On other errors: error toast with API error message
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 16.3_

- [x] 6. Checkpoint - Verify roles list tab renders and CRUD operations work
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Permission Matrix Tab (Tab 2)
  - [x] 7.1 Implement PermissionMatrixTab container
    - Create `src/features/roles/components/PermissionMatrixTab.tsx` — container with search input, unsaved changes indicator, "Save" button, matrix grid, and pagination
    - Fetch roles (paginated, with search filter), resources (all), and operations (all) using query hooks
    - Fetch permissions for all visible roles using `useMultipleRolePermissions`
    - "Save" button enabled only when `matrixStore.hasChanges()` is true
    - On save: build `BatchRolePermissionRequest` from dirty roles + local state merged with server state, call `useBatchUpdatePermissions`
    - On save success: reset matrix store, show success toast
    - On save failure: show error toast with message, preserve local changes
    - Display unsaved changes indicator text (`roles.matrix.unsavedChanges` i18n key) when dirty
    - _Requirements: 6.1, 6.7, 6.8, 6.9, 6.10, 6.11, 6.12, 6.15, 6.16_

  - [x] 7.2 Implement PermissionMatrix grid
    - Create `src/features/roles/components/PermissionMatrix.tsx` — table with role names as rows and resource names as columns
    - Sticky first column (role names) with `sticky left-0 z-10` and explicit background for narrow viewports
    - Horizontal scroll for resource columns on viewport < 1024px
    - Display resource names and operation names in current locale (pre-resolved by backend)
    - All 4 operation letters visible in every cell at all viewport widths (no collapsing)
    - _Requirements: 6.2, 6.3, 6.13, 6.14, 6.17, 7.5, 7.6, 16.4_

  - [x] 7.3 Implement OperationCell component
    - Create `src/features/roles/components/OperationCell.tsx` — renders 4 operation letter buttons (C, R, U, D) in compact `w-6 h-6` size
    - Active letters: primary color; inactive letters: muted-foreground (#a1a1aa)
    - Determine effective state: local override (from matrix store) takes precedence over server state
    - On click: call `matrixStore.toggleOperation(roleId, resourceId, operationId, currentActive)`
    - _Requirements: 6.3, 6.4, 6.5, 15.2_

- [x] 8. i18n translations and navigation integration
  - [x] 8.1 Add roles namespace translations (PL and RU)
    - Add `roles` key to `src/locales/pl.json` with all translation keys: tabs, page title, table headers, form labels, button labels, dialog text, toast messages, validation messages, empty states, badge labels, matrix-specific labels
    - Add `roles` key to `src/locales/ru.json` with equivalent Russian translations
    - Include specific keys: `roles.tabs.list` (PL: "Role", RU: "Роли"), `roles.tabs.matrix` (PL: "Matryca dostępu", RU: "Матрица доступов"), `roles.matrix.save`, `roles.matrix.search`, `roles.matrix.unsavedChanges`, `roles.pageTitle`, `roles.errors.network`, `roles.errors.systemDelete`
    - _Requirements: 8.1, 8.2, 8.5, 8.6_

  - [x] 8.2 Integrate Roles page into App Shell navigation and router
    - Add lazy-loaded `RolesPage` import and `/roles` route to `src/app/router.tsx`
    - Add `nav.roles` key with Lucide `Shield` icon to the navigation config in the "System" section
    - Set Top_Bar page title from i18n key `roles.pageTitle` when on `/roles` route
    - _Requirements: 14.1, 14.2, 14.3, 14.4_

- [x] 9. Error handling and inline error states
  - [x] 9.1 Implement inline error state component and error integration
    - Create a reusable inline error component with error icon, localized message, and "Retry" button
    - Integrate into RolesListTab: show inline error with retry when list query fails after all retries
    - Integrate into PermissionMatrixTab: show inline error with retry when matrix data fails
    - Ensure mutation buttons show loading spinner and are disabled during pending state
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5_

- [x] 10. Dark theme and responsive polish
  - [x] 10.1 Apply dark theme tokens and responsive breakpoints
    - Verify all components use design tokens: background (#09090b), foreground (#fafafa), border (#27272a), muted (#27272a), muted-foreground (#a1a1aa)
    - Verify toast colors: success (#22c55e), error/destructive (#7f1d1d)
    - Verify system badge uses muted background with muted-foreground text
    - Verify responsive breakpoints: mobile (<768px), tablet (768–1024px), desktop (>1024px)
    - Verify tablet layout: table visible, action buttons in dropdown menu
    - Verify tab bar remains visible at all viewport widths
    - _Requirements: 7.1, 7.2, 7.3, 7.5, 7.6, 7.7, 15.1, 15.2, 15.3, 15.4_

- [x] 11. Final checkpoint - Full integration verification
  - Ensure all tests pass, ask the user if questions arise.

- [x] 12. Write unit and component tests
  - [x] 12.1 Write unit tests for Zod schema validation
    - Test valid create payload passes
    - Test code pattern enforcement (lowercase fails, digit-start fails)
    - Test name min/max length boundaries
    - Test description max length
    - Test system defaults to false
    - Test update schema omits code
    - _Requirements: 10.2_

  - [x] 12.2 Write unit tests for API error handling
    - Test `handleResponse` throws `ApiError` for non-ok responses
    - Test error message parsing from JSON body
    - Test fallback when JSON parsing fails
    - _Requirements: 11.1, 11.2_

  - [x] 12.3 Write component tests for RolesPage tab switching
    - Test renders two tabs with correct i18n labels
    - Test defaults to "list" tab active
    - Test switching tabs renders correct content
    - _Requirements: 5.1, 5.2, 5.3, 5.4_

  - [x] 12.4 Write component tests for RolesListTab
    - Test skeleton display while loading
    - Test table rows render with role data on desktop
    - Test cards render on mobile viewport
    - Test search triggers filtered refetch
    - Test pagination controls
    - Test system badge and disabled delete for system roles
    - Test empty state message
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.8, 12.1, 12.2, 13.1_

  - [x] 12.5 Write component tests for RoleFormSheet
    - Test create mode: empty fields, code editable
    - Test edit mode: pre-populated, code disabled
    - Test validation errors shown inline
    - Test successful create/update flows
    - Test API error handling
    - _Requirements: 2.1, 2.2, 2.3, 3.1, 3.2, 10.3, 10.4, 10.5_

  - [x] 12.6 Write component tests for PermissionMatrix
    - Test skeleton while loading
    - Test grid renders role rows and resource columns
    - Test operation letters show correct active/inactive state
    - Test click toggles local state
    - Test save button enable/disable logic
    - Test unsaved changes indicator
    - Test batch save flow (success and failure)
    - _Requirements: 6.2, 6.3, 6.4, 6.5, 6.7, 6.8, 6.9, 6.10, 6.15_

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- The design does NOT include Correctness Properties (this is a UI module), so no property-based tests are included — only example-based unit/component tests
- The backend batch endpoint (task 1) can be implemented in parallel with the frontend API layer (task 2) since they are independent codebases
- The permission matrix uses a single `PUT /api/roles/permissions/batch` for all-or-nothing save semantics
- Resources and operations in the matrix use pre-resolved locale names from the backend (Accept-Language header)
- The roles list uses `/api/roles/extended` to get the `system` field for badge display

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1"] },
    { "id": 1, "tasks": ["2.2", "2.3"] },
    { "id": 2, "tasks": ["2.4", "2.5", "4.1"] },
    { "id": 3, "tasks": ["4.2", "5.1", "5.3"] },
    { "id": 4, "tasks": ["5.2", "5.4", "5.5"] },
    { "id": 5, "tasks": ["7.1", "8.1"] },
    { "id": 6, "tasks": ["7.2", "7.3", "8.2"] },
    { "id": 7, "tasks": ["9.1", "10.1"] },
    { "id": 8, "tasks": ["12.1", "12.2", "12.3"] },
    { "id": 9, "tasks": ["12.4", "12.5", "12.6"] }
  ]
}
```
