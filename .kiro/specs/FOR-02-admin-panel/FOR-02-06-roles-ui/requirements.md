# Requirements Document

## Introduction

FOR-02-06 — Roles UI: a frontend page for managing roles and the summary permission matrix in the Foremen admin panel. This spec implements a tabbed interface: Tab 1 provides the CRUD interface for roles (list, create, edit, delete), and Tab 2 provides a summary permission matrix showing ALL roles × ALL resources with togglable operation letters. The page integrates with the backend REST API defined in FOR-02-03 (ABAC Entities) and operates within the App Shell established in FOR-02-02.

## Glossary

- **Roles_Page**: The top-level page component at route `/roles` containing two tabs: Roles_List_Tab and Permission_Matrix_Tab
- **Roles_List_Tab**: The first tab ("Roles") displaying the paginated roles CRUD list
- **Permission_Matrix_Tab**: The second tab ("Permission Matrix" / "Матрица доступов") displaying the summary permission matrix for all roles × all resources
- **Roles_List**: A paginated table displaying all roles with search/filter capability
- **Role_Form**: A form component (React Hook Form + Zod) used for both creating and editing a role
- **Permission_Matrix**: A summary grid/table view with role names as rows and resource names as columns, where each cell displays operation letters (C, R, U, D) that are individually togglable
- **Operation_Letter**: A clickable letter (C for CREATE, R for READ, U for UPDATE, D for DELETE) rendered inside a Permission_Matrix cell; toggling changes the local permission state
- **System_Role**: A role with `system=true` that cannot be deleted; visually indicated with a badge
- **Role_Service**: The TanStack Query hooks and API client layer responsible for fetching and mutating role data via REST endpoints
- **Toast_Notification**: A transient notification (success or error) displayed after mutation operations
- **Skeleton_Loader**: Placeholder UI with shimmer animation displayed while data is loading
- **Delete_Dialog**: A confirmation dialog displayed before deleting a non-system role
- **i18n_Namespace**: The i18next namespace "roles" containing all localized strings for this page

## Requirements

### Requirement 1: Roles List Page (Tab 1)

**User Story:** As an admin, I want to see a paginated list of all roles so that I can browse and manage the access control configuration.

#### Acceptance Criteria

1. WHEN the user navigates to `/roles`, THE Roles_Page SHALL render the Roles_List_Tab as the default active tab, displaying roles fetched from GET `/api/roles` with pagination parameters
2. THE Roles_List SHALL display each role in a table row containing: code, localized name (nameRU or namePL based on current locale), description, system badge (if system=true), and action buttons (edit, delete)
3. THE Roles_List SHALL support server-side pagination with page size of 10, displaying pagination controls (previous/next, current page indicator)
4. THE Roles_List SHALL provide a search input that filters roles by code or name via the API query parameter
5. WHEN the Roles_List is loading data, THE Roles_Page SHALL display the Skeleton_Loader with table row placeholders and shimmer animation
6. THE Roles_List SHALL display an empty state message (localized) when no roles match the current filter criteria
7. THE Roles_List SHALL display a "Create Role" button that navigates to the role creation form
8. WHILE viewport width is less than 768px, THE Roles_List SHALL render as a card-based layout (one role per card, stacked vertically) instead of a table

---

### Requirement 2: Create Role

**User Story:** As an admin, I want to create a new role with localized names so that I can define custom access control groups.

#### Acceptance Criteria

1. WHEN the user clicks the "Create Role" button, THE Roles_Page SHALL display the Role_Form in creation mode (empty fields)
2. THE Role_Form SHALL contain fields: code (required, alphanumeric + underscore, uppercase), nameRU (required), namePL (required), description (optional), system flag (checkbox, defaults to false)
3. THE Role_Form SHALL validate inputs using Zod schema: code is required with pattern `^[A-Z][A-Z0-9_]{1,49}$`, nameRU and namePL are required with min length 2 and max length 100
4. WHEN the user submits a valid Role_Form in creation mode, THE Role_Service SHALL send a POST request to `/api/roles` with the form data
5. WHEN the POST request succeeds, THE Roles_Page SHALL display a success Toast_Notification, invalidate the roles list query cache, and navigate back to the Roles_List
6. IF the POST request fails, THEN THE Roles_Page SHALL display an error Toast_Notification with the error message from the API response
7. WHEN the user clicks "Cancel" on the Role_Form, THE Roles_Page SHALL navigate back to the Roles_List without saving

---

### Requirement 3: Edit Role

**User Story:** As an admin, I want to edit an existing role so that I can update its name, description, or system flag.

#### Acceptance Criteria

1. WHEN the user clicks the "Edit" action on a role row, THE Roles_Page SHALL display the Role_Form in edit mode pre-populated with the role data fetched from GET `/api/roles/{id}`
2. THE Role_Form in edit mode SHALL disable the `code` field (code is immutable after creation)
3. WHEN the user submits a valid Role_Form in edit mode, THE Role_Service SHALL send a PUT request to `/api/roles/{id}` with the updated fields
4. WHEN the PUT request succeeds, THE Roles_Page SHALL display a success Toast_Notification, invalidate the roles list and role detail query caches, and navigate back to the Roles_List
5. IF the PUT request fails, THEN THE Roles_Page SHALL display an error Toast_Notification with the error message from the API response
6. WHILE the Role_Form is loading role data for editing, THE Roles_Page SHALL display the Skeleton_Loader in place of the form fields

---

### Requirement 4: Delete Role

**User Story:** As an admin, I want to delete a custom role so that I can remove obsolete access control groups.

#### Acceptance Criteria

1. WHEN the user clicks the "Delete" action on a non-system role, THE Roles_Page SHALL display the Delete_Dialog with a confirmation message including the role name
2. WHEN the user confirms deletion in the Delete_Dialog, THE Role_Service SHALL send a DELETE request to `/api/roles/{id}`
3. WHEN the DELETE request succeeds, THE Roles_Page SHALL display a success Toast_Notification, invalidate the roles list query cache, and close the Delete_Dialog
4. IF the DELETE request returns HTTP 403, THEN THE Roles_Page SHALL display an error Toast_Notification indicating that system roles cannot be deleted
5. IF the DELETE request fails with any other error, THEN THE Roles_Page SHALL display an error Toast_Notification with the error message
6. WHILE a role has `system=true`, THE Roles_List SHALL render the delete action button as disabled with a tooltip explaining that system roles cannot be deleted

---

### Requirement 5: Tabbed Layout

**User Story:** As an admin, I want the Roles page organized into two tabs so that I can switch between managing individual roles and viewing the overall permission matrix.

#### Acceptance Criteria

1. THE Roles_Page SHALL render two tabs: Roles_List_Tab (label from i18n key `roles.tabs.list`) and Permission_Matrix_Tab (label from i18n key `roles.tabs.matrix`)
2. WHEN the user navigates to `/roles`, THE Roles_Page SHALL display the Roles_List_Tab as the active tab by default
3. WHEN the user clicks the Permission_Matrix_Tab, THE Roles_Page SHALL switch to the Permission_Matrix view without a page reload
4. WHEN the user clicks the Roles_List_Tab, THE Roles_Page SHALL switch back to the Roles_List view without a page reload
5. THE tab selection state SHALL be preserved when navigating back to the Roles_Page from a sub-route (e.g., returning from the edit form)
6. THE Roles_Page SHALL use the shadcn/ui Tabs component for tab rendering and state management

---

### Requirement 6: Summary Permission Matrix (Tab 2)

**User Story:** As an admin, I want to view and edit permissions for ALL roles in a single matrix so that I can quickly compare and adjust access across the entire system.

#### Acceptance Criteria

1. WHEN the user activates the Permission_Matrix_Tab, THE Permission_Matrix SHALL fetch roles from GET `/api/roles` (paginated, with search filter), resources from GET `/api/resources` (all), and operations from GET `/api/operations` (all)
2. THE Permission_Matrix SHALL render a grid with role names as rows and resource names as columns
3. FOR EACH cell in the Permission_Matrix, THE cell SHALL display Operation_Letters (C, R, U, D) representing all available operations
4. FOR EACH Operation_Letter in a cell, THE Permission_Matrix SHALL fetch current permissions from GET `/api/roles/{id}/permissions` for the corresponding role and render the letter as active (highlighted) if the role has that operation on that resource, or inactive (muted) otherwise
5. WHEN the user clicks an Operation_Letter, THE Permission_Matrix SHALL toggle its state locally (active to inactive or vice versa) without immediately calling the API
6. THE Permission_Matrix SHALL accumulate all local changes and track which roles have been modified
7. THE Permission_Matrix SHALL display a "Save" button (label from i18n key `roles.matrix.save`) that becomes enabled when at least one permission change exists in the local state
8. WHEN the user clicks "Save", THE Role_Service SHALL send PUT `/api/roles/{id}/permissions` requests for each modified role with the full permission payload (list of resource_id + operation_ids)
9. WHEN all PUT permission requests succeed, THE Roles_Page SHALL display a success Toast_Notification and reset the local change tracking state
10. IF any PUT permission request fails, THEN THE Roles_Page SHALL display an error Toast_Notification identifying which role(s) failed to save
11. THE Permission_Matrix SHALL display a search input (placeholder from i18n key `roles.matrix.search`) that filters displayed roles by name
12. THE Permission_Matrix SHALL support pagination for rows (roles) with page size of 10
13. THE Permission_Matrix SHALL display ALL resource columns without pagination or hiding
14. THE Permission_Matrix SHALL always display all four Operation_Letters in every cell (no collapsing or hiding of letters)
15. WHILE the Permission_Matrix has unsaved changes, THE Permission_Matrix SHALL display an indicator (text from i18n key `roles.matrix.unsavedChanges`) informing the user of pending changes
16. WHILE the Permission_Matrix data is loading, THE Roles_Page SHALL display the Skeleton_Loader with a grid placeholder
17. THE Permission_Matrix SHALL display resource names and operation names in the current locale (nameRU or namePL)

---

### Requirement 7: Responsive Design (Mobile-First)

**User Story:** As a user on any device, I want the roles management page to be usable on mobile, tablet, and desktop so that I can manage roles from anywhere.

#### Acceptance Criteria

1. WHILE viewport width is less than 768px, THE Roles_Page SHALL use a single-column layout with full-width cards for role items and stacked form fields
2. WHILE viewport width is between 768px and 1024px, THE Roles_Page SHALL use a tablet-optimized layout with the table visible but action buttons collapsed into a dropdown menu
3. WHILE viewport width is greater than 1024px, THE Roles_Page SHALL use a full desktop layout with the data table, inline action buttons, and side-by-side form fields where appropriate
4. THE Role_Form SHALL render form fields in a single column on mobile and two columns (nameRU / namePL side by side) on desktop
5. WHILE viewport width is less than 1024px, THE Permission_Matrix SHALL enable horizontal scrolling with a sticky first column (role names) so that the user can scroll through resource columns while keeping role identification visible
6. THE Permission_Matrix Operation_Letters SHALL remain visible in all cells at all viewport widths (no collapsing into accordions or hidden sections)
7. THE tab bar SHALL remain fully visible and accessible at all viewport widths

---

### Requirement 8: Internationalization (i18n)

**User Story:** As a user, I want all roles management UI text in my chosen language (PL or RU) so that I can work comfortably.

#### Acceptance Criteria

1. THE Roles_Page SHALL render all user-facing text via i18next keys in the "roles" namespace
2. THE Roles_Page SHALL provide translations for both "pl" and "ru" locales covering: page titles, tab labels, table headers, form labels, button labels, dialog text, toast messages, validation messages, empty states, badge labels, and matrix-specific labels
3. WHEN the user switches locale via the Top_Bar language switcher, THE Roles_Page SHALL update all text labels without a page reload
4. THE Roles_Page SHALL display entity names (role name, resource name, operation name) in the locale-appropriate field (namePL for "pl" locale, nameRU for "ru" locale) as returned by the API
5. IF a translation key is missing in the current locale, THEN THE Roles_Page SHALL fall back to the "pl" locale value
6. THE i18n_Namespace SHALL include keys: `roles.tabs.list` (PL: "Role", RU: "Роли"), `roles.tabs.matrix` (PL: "Matryca dostępu", RU: "Матрица доступов"), `roles.matrix.save` (PL: "Zapisz", RU: "Сохранить"), `roles.matrix.search` (PL: "Szukaj roli...", RU: "Поиск роли..."), `roles.matrix.unsavedChanges` (PL: "Masz niezapisane zmiany", RU: "Есть несохранённые изменения")

---

### Requirement 9: Data Fetching and Caching (TanStack Query)

**User Story:** As a developer, I want a structured data fetching layer using TanStack Query so that API calls are cached, deduplicated, and mutation states are properly managed.

#### Acceptance Criteria

1. THE Role_Service SHALL define TanStack Query hooks: `useRoles` (list, paginated), `useRole` (single by ID), `useResources` (list, all), `useOperations` (list, all), `useRolePermissions` (by role ID), `useMultipleRolePermissions` (batch fetch for visible roles in the matrix)
2. THE Role_Service SHALL define TanStack Mutation hooks: `useCreateRole`, `useUpdateRole`, `useDeleteRole`, `useUpdatePermissions`, `useBatchUpdatePermissions` (for saving the entire matrix)
3. WHEN a mutation succeeds, THE Role_Service SHALL invalidate related query caches to trigger background refetch
4. THE Role_Service SHALL configure query stale time of 30 seconds for list queries and 60 seconds for detail queries
5. WHEN a query request fails, THE Role_Service SHALL retry up to 2 times with exponential backoff before surfacing the error
6. THE Role_Service SHALL expose loading and error states from queries and mutations for UI rendering

---

### Requirement 10: Form Validation (React Hook Form + Zod)

**User Story:** As a developer, I want form validation handled by React Hook Form with Zod schemas so that validation is type-safe, consistent, and performant.

#### Acceptance Criteria

1. THE Role_Form SHALL use React Hook Form with `@hookform/resolvers/zod` for schema-based validation
2. THE Role_Form SHALL define a Zod schema: code (string, required, regex `^[A-Z][A-Z0-9_]{1,49}$`), nameRU (string, required, min 2, max 100), namePL (string, required, min 2, max 100), description (string, optional, max 500), system (boolean, optional, default false)
3. WHEN the user submits the form with validation errors, THE Role_Form SHALL display inline error messages below each invalid field in the current locale
4. THE Role_Form SHALL validate on blur for individual fields and on submit for the entire form
5. THE Role_Form SHALL disable the submit button while a mutation is in progress to prevent double submission

---

### Requirement 11: Error Handling

**User Story:** As a user, I want clear error feedback when something goes wrong so that I understand what happened and can take corrective action.

#### Acceptance Criteria

1. WHEN an API request fails with a network error, THE Roles_Page SHALL display an error Toast_Notification with a generic connectivity message
2. WHEN an API request fails with an HTTP error (4xx or 5xx), THE Roles_Page SHALL display an error Toast_Notification containing the error message from the response body
3. IF loading the Roles_List fails after all retries, THEN THE Roles_Page SHALL display an inline error state with a "Retry" button that re-triggers the query
4. IF loading the Permission_Matrix fails after all retries, THEN THE Roles_Page SHALL display an inline error state with a "Retry" button
5. WHEN a mutation (create/update/delete/save matrix) is in progress, THE Roles_Page SHALL disable the triggering button and display a loading indicator

---

### Requirement 12: System Role Protection

**User Story:** As an admin, I want system roles to be visually distinct and protected from deletion so that critical roles cannot be accidentally removed.

#### Acceptance Criteria

1. THE Roles_List SHALL display a "System" badge (localized) next to roles where `system=true`
2. WHILE a role has `system=true`, THE Roles_List SHALL render the delete action as disabled (visually muted, non-clickable) with a tooltip explaining the restriction
3. THE Role_Form in edit mode for a system role SHALL display the system flag checkbox as checked and disabled (cannot be unchecked for existing system roles)
4. THE Permission_Matrix SHALL allow editing permissions for system roles (permissions are editable even for system roles; only deletion is restricted)

---

### Requirement 13: Skeleton Loading States

**User Story:** As a user, I want to see loading placeholders while data is being fetched so that I understand the page is loading rather than empty.

#### Acceptance Criteria

1. WHILE the roles list query is in loading state, THE Roles_Page SHALL display 5 skeleton table rows (or 5 skeleton cards on mobile) with shimmer animation
2. WHILE the role detail query is in loading state, THE Role_Form SHALL display skeleton placeholders for each form field
3. WHILE the permission matrix data is loading, THE Permission_Matrix SHALL display a skeleton grid with placeholder cells matching the expected matrix dimensions (rows for roles, columns for resources)
4. THE Skeleton_Loader SHALL use a CSS pulse animation with a cycle duration between 1.5 and 2 seconds

---

### Requirement 14: Navigation Integration

**User Story:** As a developer, I want the Roles page integrated into the App Shell navigation so that users can access it from the sidebar.

#### Acceptance Criteria

1. THE App_Shell navigation configuration SHALL include a Nav_Item for Roles with route `/roles`, i18n key `nav.roles`, and Lucide icon `Shield`
2. THE Nav_Item for Roles SHALL be placed in the "System" navigation section
3. WHEN the user is on the `/roles` route, THE Top_Bar SHALL display the page title from i18n key `roles.pageTitle`
4. WHEN the user navigates to a role creation or edit sub-route, THE Router SHALL render the Role_Form within the Roles_Page layout

---

### Requirement 15: Dark Theme Consistency

**User Story:** As a user, I want the roles page to be visually consistent with the rest of the dark-themed application.

#### Acceptance Criteria

1. THE Roles_Page SHALL use design tokens from the Design_Token_System: background (#09090b), foreground (#fafafa), border (#27272a), muted (#27272a), muted-foreground (#a1a1aa)
2. THE Permission_Matrix active Operation_Letters SHALL use the primary color for active state and muted-foreground color (#a1a1aa) for inactive state
3. THE success Toast_Notification SHALL use the success color (#22c55e) and the error Toast_Notification SHALL use the destructive color (#7f1d1d)
4. THE System_Role badge SHALL use a distinct visual treatment (muted background with muted-foreground text) to differentiate from action badges

---

### Requirement 16: UI Components (shadcn/ui)

**User Story:** As a developer, I want to use shadcn/ui components so that the UI is accessible, consistent, and maintainable.

#### Acceptance Criteria

1. THE Roles_List SHALL use the shadcn/ui Table component (Table, TableHeader, TableBody, TableRow, TableCell) for the desktop view
2. THE Role_Form SHALL use shadcn/ui Form components (Form, FormField, FormItem, FormLabel, FormControl, FormMessage) integrated with React Hook Form
3. THE Delete_Dialog SHALL use the shadcn/ui AlertDialog component with localized title, description, and action buttons
4. THE Permission_Matrix SHALL use the shadcn/ui Table component for the matrix grid layout
5. THE Roles_Page SHALL use the shadcn/ui Tabs component (Tabs, TabsList, TabsTrigger, TabsContent) for the tabbed layout
6. THE Roles_Page SHALL use the shadcn/ui Button component for all interactive buttons with appropriate variants (default, destructive, outline, ghost)
7. THE Toast_Notification SHALL use the shadcn/ui Toast component (via Sonner integration or shadcn Toast)
8. THE System_Role badge SHALL use the shadcn/ui Badge component with the "secondary" variant
