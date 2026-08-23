# Requirements Document

## Introduction

FOR-02-07 — Users UI: a frontend page for managing users in the Foremen admin panel. The page lives at route `/users` and provides a CRUD interface (list, create, edit, deactivate) with role assignment, locale selection, and audit log integration. It follows the same patterns as the Roles UI (FOR-02-06): the reusable DataTable component for listing, sheet-based form dialogs for create/edit, and an audit modal per row. The backend already provides full CRUD via `UserController` implementing `AdminController`.

## Glossary

- **Users_Page**: The top-level page component at route `/users` displaying the users DataTable and hosting form/dialog overlays
- **Users_List**: The DataTable instance displaying all users with search, sort, filter, and pagination
- **User_Form**: A sheet-based form component (React Hook Form + Zod) used for both creating and editing a user
- **Delete_Dialog**: A confirmation dialog displayed before deactivating a user (soft-delete sets `active=false`)
- **User_Service**: The TanStack Query hooks and API client layer responsible for fetching and mutating user data via REST endpoints
- **Toast_Notification**: A transient notification (success or error) displayed after mutation operations
- **Skeleton_Loader**: Placeholder UI with shimmer animation displayed while data is loading
- **DataTable**: The reusable generic table component at `src/components/data-table/` providing search, sort, filter, pagination, skeleton, empty states, and audit button
- **i18n_Namespace**: The i18next namespace "users" containing all localized strings for this page
- **Active_Badge**: A visual indicator showing the user's active/inactive status
- **Phone_Input**: An international phone number input component with country code selector (powered by `react-phone-number-input` library, validates via `libphonenumber-js`)
- **Role_Select**: A dropdown field in User_Form for assigning a role to the user

## Requirements

### Requirement 1: Users List Page

**User Story:** As an admin, I want to see a paginated list of all users so that I can browse and manage user accounts.

#### Acceptance Criteria

1. WHEN the user navigates to `/users`, THE Users_Page SHALL render the Users_List by fetching data from GET `/api/users` with pagination, sort, and filter parameters
2. THE Users_List SHALL display each user in a table row containing: name, email, role name (localized), active status badge, and action buttons (edit, deactivate, audit)
3. THE Users_List SHALL use the DataTable component with `entityKey="users"` and default page size of 25
4. THE Users_List SHALL define the following columns: name (string, sortable, filterable, searchable), email (string, sortable, filterable, searchable), roleName (string, sortable, filterable, searchable), active (boolean, sortable, filterable)
5. THE Users_List SHALL pass `showAuditButton={true}` to the DataTable to enable the audit log button per row
6. WHEN the Users_List is loading data, THE DataTable SHALL display the Skeleton_Loader with table row placeholders and shimmer animation
7. THE Users_List SHALL display a localized empty state message when no users match the current filter criteria
8. THE Users_List SHALL display a "Create User" button above the table that opens the User_Form in creation mode
9. THE Users_List SHALL render the active column with a visual badge: green "Active" badge for `active=true`, muted "Inactive" badge for `active=false`

---

### Requirement 2: Create User

**User Story:** As an admin, I want to create a new user with name, email, role assignment, and locale so that I can add people to the system.

#### Acceptance Criteria

1. WHEN the user clicks the "Create User" button, THE Users_Page SHALL display the User_Form in a sheet overlay in creation mode (empty fields)
2. THE User_Form SHALL contain fields: name (required, text input), email (required, email format), phone (optional, Phone_Input with country code selector, default country PL), roleId (required, select dropdown), locale (required, select dropdown with options: ru, pl, en), active (checkbox, defaults to true)
3. THE User_Form SHALL validate inputs using Zod schema: name is required with min length 2 and max length 100, email is required with valid email format, phone is optional and validated as a valid international phone number using libphonenumber-js `isValidPhoneNumber()` (E.164 format, e.g., +48789736625), roleId is required (number), locale is required and must be one of "ru", "pl", "en"
4. THE Role_Select in User_Form SHALL fetch available roles from GET `/api/roles` and display them as dropdown options with localized names
5. WHEN the user submits a valid User_Form in creation mode, THE User_Service SHALL send a POST request to `/api/users` with the form data
6. WHEN the POST request succeeds, THE Users_Page SHALL display a success Toast_Notification, close the form sheet, and refresh the Users_List data
7. IF the POST request fails with HTTP 409 (email uniqueness violation), THEN THE Users_Page SHALL display an error Toast_Notification indicating the email already exists
8. IF the POST request fails with any other error, THEN THE Users_Page SHALL display an error Toast_Notification with the error message from the API response
9. WHEN the user clicks "Cancel" on the User_Form, THE Users_Page SHALL close the form sheet without saving

---

### Requirement 3: Edit User

**User Story:** As an admin, I want to edit an existing user so that I can update their name, email, role, locale, or active status.

#### Acceptance Criteria

1. WHEN the user clicks the "Edit" action on a user row, THE Users_Page SHALL display the User_Form in a sheet overlay in edit mode pre-populated with the user data fetched from GET `/api/users/{id}`
2. THE User_Form in edit mode SHALL allow editing all fields: name, email, phone, roleId, locale, and active status
3. WHEN the user submits a valid User_Form in edit mode, THE User_Service SHALL send a PUT request to `/api/users/{id}` with the updated fields
4. WHEN the PUT request succeeds, THE Users_Page SHALL display a success Toast_Notification, close the form sheet, and refresh the Users_List data
5. IF the PUT request fails with HTTP 409 (email uniqueness violation), THEN THE Users_Page SHALL display an error Toast_Notification indicating the email already exists
6. IF the PUT request fails with any other error, THEN THE Users_Page SHALL display an error Toast_Notification with the error message from the API response
7. WHILE the User_Form is loading user data for editing, THE Users_Page SHALL display the Skeleton_Loader in place of the form fields

---

### Requirement 4: Deactivate User (Soft-Delete)

**User Story:** As an admin, I want to deactivate a user so that I can revoke their access without permanently deleting their record.

#### Acceptance Criteria

1. WHEN the user clicks the "Deactivate" action on an active user row, THE Users_Page SHALL display the Delete_Dialog with a confirmation message including the user's name
2. THE Delete_Dialog SHALL explain that deactivation is a soft-delete operation (the user record is preserved but marked inactive)
3. WHEN the user confirms deactivation in the Delete_Dialog, THE User_Service SHALL send a DELETE request to `/api/users/{id}`
4. WHEN the DELETE request succeeds, THE Users_Page SHALL display a success Toast_Notification, close the Delete_Dialog, and refresh the Users_List data
5. IF the DELETE request fails, THEN THE Users_Page SHALL display an error Toast_Notification with the error message
6. WHILE a user has `active=false`, THE Users_List SHALL render the deactivate action button as disabled with a tooltip explaining the user is already inactive

---

### Requirement 5: Role Assignment

**User Story:** As an admin, I want to assign a role to a user during creation or editing so that the user receives appropriate access permissions.

#### Acceptance Criteria

1. THE User_Form SHALL include a Role_Select dropdown that fetches all roles from GET `/api/roles` (paginated, all pages) and displays them with localized names
2. THE Role_Select SHALL be a required field — the form cannot be submitted without a role selected
3. THE Role_Select SHALL display the currently assigned role as the default selected value in edit mode
4. IF the roles list fails to load, THEN THE Role_Select SHALL display an error state with a retry button
5. THE Role_Select SHALL support searching/filtering roles by name within the dropdown

---

### Requirement 6: Audit Log Integration

**User Story:** As an admin, I want to view the audit history of a user so that I can track changes made to their record.

#### Acceptance Criteria

1. THE Users_List SHALL include an audit button per row (provided automatically by the DataTable component with `showAuditButton={true}`)
2. WHEN the user clicks the audit button on a user row, THE DataTable SHALL open the AuditModal fetching data from GET `/api/users/audit/{id}`
3. THE AuditModal SHALL display audit entries with operation type (CREATE, UPDATE, DEACTIVATE), performer, timestamp, and snapshot comparison (before/after)

---

### Requirement 7: Internationalization (i18n)

**User Story:** As a user, I want all user management UI text in my chosen language (PL or RU) so that I can work comfortably.

#### Acceptance Criteria

1. THE Users_Page SHALL render all user-facing text via i18next keys in the "users" namespace
2. THE Users_Page SHALL provide translations for both "pl" and "ru" locales covering: page title, table column headers, form labels, button labels, dialog text, toast messages, validation messages, empty states, badge labels, and select options
3. WHEN the user switches locale via the Top_Bar language switcher, THE Users_Page SHALL update all text labels without a page reload
4. THE Users_Page SHALL display entity names (role name) in the locale-appropriate field as returned by the API (pre-resolved via Accept-Language header)
5. IF a translation key is missing in the current locale, THEN THE Users_Page SHALL fall back to the "pl" locale value
6. THE i18n_Namespace SHALL include keys for: `users.pageTitle` (PL: "Zarządzanie użytkownikami", RU: "Управление пользователями"), `users.actions.create` (PL: "Utwórz użytkownika", RU: "Создать пользователя"), `users.table.name` (PL: "Imię", RU: "Имя"), `users.table.email` (PL: "E-mail", RU: "E-mail"), `users.table.role` (PL: "Rola", RU: "Роль"), `users.table.active` (PL: "Aktywny", RU: "Активен"), `users.table.actions` (PL: "Akcje", RU: "Действия")

---

### Requirement 8: Responsive Design

**User Story:** As a user on any device, I want the users management page to be usable on mobile, tablet, and desktop so that I can manage users from anywhere.

#### Acceptance Criteria

1. WHILE viewport width is less than 768px, THE DataTable SHALL render users as a card-based layout (one user per card, stacked vertically) with all key fields visible
2. WHILE viewport width is between 768px and 1024px, THE DataTable SHALL render the table with horizontal scrolling enabled and action buttons collapsed into a dropdown menu
3. WHILE viewport width is greater than 1024px, THE DataTable SHALL render the full desktop table layout with inline action buttons
4. THE User_Form SHALL render form fields in a single column on mobile and two columns on desktop (name/email side by side, phone/locale side by side)

---

### Requirement 9: Dark Theme Consistency

**User Story:** As a user, I want the users page to be visually consistent with the rest of the dark-themed application.

#### Acceptance Criteria

1. THE Users_Page SHALL use design tokens from the Design_Token_System: background (#09090b), foreground (#fafafa), border (#27272a), muted (#27272a), muted-foreground (#a1a1aa)
2. THE Active_Badge for active users SHALL use the success color (#22c55e) and the badge for inactive users SHALL use the muted color with muted-foreground text
3. THE success Toast_Notification SHALL use the success color (#22c55e) and the error Toast_Notification SHALL use the destructive color (#7f1d1d)
4. THE Role_Select dropdown SHALL use the popover design tokens consistent with other select components in the application

---

### Requirement 10: Data Fetching and Caching (TanStack Query)

**User Story:** As a developer, I want a structured data fetching layer using TanStack Query so that API calls are cached, deduplicated, and mutation states are properly managed.

#### Acceptance Criteria

1. THE User_Service SHALL define TanStack Query hooks: `useUser` (single by ID for edit form), `useRolesForSelect` (list of roles for the Role_Select dropdown)
2. THE User_Service SHALL define TanStack Mutation hooks: `useCreateUser`, `useUpdateUser`, `useDeactivateUser`
3. WHEN a mutation succeeds, THE User_Service SHALL invalidate the users list query cache to trigger background refetch
4. THE User_Service SHALL configure query stale time of 30 seconds for the user detail query and 60 seconds for the roles dropdown query
5. WHEN a query request fails, THE User_Service SHALL retry up to 2 times with exponential backoff before surfacing the error
6. THE Users_List data fetching SHALL be handled by the DataTable component via the provided `fetchFn` prop (no separate `useUsers` hook needed)

---

### Requirement 11: Form Validation (React Hook Form + Zod)

**User Story:** As a developer, I want form validation handled by React Hook Form with Zod schemas so that validation is type-safe, consistent, and performant.

#### Acceptance Criteria

1. THE User_Form SHALL use React Hook Form with `@hookform/resolvers/zod` for schema-based validation
2. THE User_Form SHALL define a Zod schema: name (string, required, min 2, max 100), email (string, required, valid email format), phone (string, optional, validated using `isValidPhoneNumber()` from libphonenumber-js — empty string is valid, but any non-empty value must be a valid international phone number in E.164 format), roleId (number, required), locale (enum of "ru" | "pl" | "en", required), active (boolean, optional, default true)
3. WHEN the user submits the form with validation errors, THE User_Form SHALL display inline error messages below each invalid field in the current locale
4. THE User_Form SHALL validate on blur for individual fields and on submit for the entire form
5. THE User_Form SHALL disable the submit button while a mutation is in progress to prevent double submission

---

### Requirement 12: Error Handling

**User Story:** As a user, I want clear error feedback when something goes wrong so that I understand what happened and can take corrective action.

#### Acceptance Criteria

1. WHEN an API request fails with a network error, THE Users_Page SHALL display an error Toast_Notification with a generic connectivity message
2. WHEN an API request fails with an HTTP error (4xx or 5xx), THE Users_Page SHALL display an error Toast_Notification containing the error message from the response body
3. IF loading the Users_List fails after all retries, THEN THE DataTable SHALL display an inline error state with a "Retry" button that re-triggers the query
4. WHEN a mutation (create/update/deactivate) is in progress, THE Users_Page SHALL disable the triggering button and display a loading indicator

---

### Requirement 13: Skeleton Loading States

**User Story:** As a user, I want to see loading placeholders while data is being fetched so that I understand the page is loading rather than empty.

#### Acceptance Criteria

1. WHILE the users list query is in loading state, THE DataTable SHALL display 5 skeleton table rows (or 5 skeleton cards on mobile) with shimmer animation
2. WHILE the user detail query is in loading state, THE User_Form SHALL display skeleton placeholders for each form field
3. THE Skeleton_Loader SHALL use a CSS pulse animation with a cycle duration between 1.5 and 2 seconds

---

### Requirement 14: Navigation Integration

**User Story:** As a developer, I want the Users page integrated into the App Shell navigation so that users can access it from the sidebar.

#### Acceptance Criteria

1. THE App_Shell navigation configuration SHALL include a Nav_Item for Users with route `/users`, i18n key `nav.users`, and Lucide icon `Users`
2. THE Nav_Item for Users SHALL be placed in the "System" navigation section alongside Roles and Audit
3. WHEN the user is on the `/users` route, THE Top_Bar SHALL display the page title from i18n key `users.pageTitle`

---

### Requirement 15: UI Components (shadcn/ui)

**User Story:** As a developer, I want to use shadcn/ui components so that the UI is accessible, consistent, and maintainable.

#### Acceptance Criteria

1. THE Users_List SHALL use the DataTable component which internally uses shadcn/ui Table for the desktop view
2. THE User_Form SHALL use shadcn/ui Form components (Form, FormField, FormItem, FormLabel, FormControl, FormMessage) integrated with React Hook Form
3. THE User_Form SHALL use shadcn/ui Sheet component for the form overlay (consistent with Roles UI pattern)
4. THE Delete_Dialog SHALL use the shadcn/ui AlertDialog component with localized title, description, and action buttons
5. THE Role_Select SHALL use the shadcn/ui Select component (or Combobox for searchable selection)
6. THE Users_Page SHALL use the shadcn/ui Button component for all interactive buttons with appropriate variants (default, destructive, outline, ghost)
7. THE Toast_Notification SHALL use the shadcn/ui Toast component (via Sonner integration)
8. THE Active_Badge SHALL use the shadcn/ui Badge component with appropriate color variants
9. THE Phone_Input SHALL use `react-phone-number-input` component styled to match shadcn/ui input design tokens (dark background, border, rounded corners)


---

### Requirement 16: Phone Number Input

**User Story:** As an admin, I want an international phone number input with country code selector so that I can enter valid phone numbers for users in any country format.

#### Acceptance Criteria

1. THE User_Form phone field SHALL use the `react-phone-number-input` component with an integrated country code selector
2. THE Phone_Input SHALL default to Poland (PL) as the initially selected country when no value is present
3. THE Phone_Input country selector SHALL display country flags and international calling codes for each option
4. WHEN a phone number is entered, THE Phone_Input SHALL store the value in E.164 format (+country_code followed by subscriber number, e.g., +48789736625)
5. THE Phone_Input SHALL validate the entered number using `isValidPhoneNumber()` from `libphonenumber-js` — the field is optional, but if any digits are entered the full number must be valid for the selected country
6. WHILE the user is typing, THE Phone_Input SHALL format the number according to the selected country's national formatting pattern
7. WHEN editing a user with an existing phone number, THE Phone_Input SHALL parse the stored E.164 value and auto-select the corresponding country in the country selector
8. THE Phone_Input SHALL support dark theme styling consistent with other form inputs (using design tokens: dark background, border color #27272a, rounded corners)
9. IF the entered phone number is invalid for the selected country, THEN THE User_Form SHALL display an inline validation error message below the phone field in the current locale
