# Implementation Plan: Users UI (FOR-02-07-users-ui)

## Overview

Implement the Users management page for the Foremen admin panel at route `/users`. This includes a DataTable-based list with CRUD operations, a sheet-based form for create/edit (with phone input, role select, locale select), and a deactivation confirmation dialog. The feature lives at `src/features/users/` following the same patterns as the Roles UI. Tech stack: React 19, TypeScript 5, TanStack Query v5, React Hook Form + Zod, shadcn/ui, Tailwind CSS 4, i18next (PL/RU). Dependencies to install: `react-phone-number-input`, `libphonenumber-js`.

## Tasks

- [x] 1. Feature module setup: types, schemas, API layer
  - [x] 1.1 Install dependencies and create feature module directory structure with TypeScript interfaces
    - Install `react-phone-number-input` and `libphonenumber-js` packages
    - Create `src/features/users/` directory with sub-folders: `api/`, `components/`, `schemas/`, `types/`
    - Create `src/features/users/types/index.ts` with interfaces: `UserDto`, `UserExtendedDto`, `UserCreateRequest`, `UserUpdateRequest`, `RoleOption`, `PaginatedResponse<T>`
    - Create `src/features/users/index.ts` barrel export (default export of UsersPage)
    - _Requirements: 10.1, 10.6_

  - [x] 1.2 Implement Zod validation schema with phone number validation
    - Create `src/features/users/schemas/user-schema.ts`
    - Define `userFormSchema` with fields: name (string, min 2, max 100), email (string, required, valid email), phone (optional, validated with `isValidPhoneNumber()` from libphonenumber-js — empty string is valid, non-empty must be valid E.164), roleId (number, required, min 1), locale (enum "ru" | "pl" | "en", required), active (boolean, default true)
    - Export inferred type `UserFormValues`
    - _Requirements: 11.1, 11.2, 16.4, 16.5_

  - [x] 1.3 Implement API client layer
    - Create `src/features/users/api/users-api.ts` with functions: `fetchUsers` (adapter for DataTable `FetchParams`), `fetchUser` (GET `/api/users/{id}`), `createUser` (POST `/api/users`), `updateUser` (PUT `/api/users/{id}`), `deactivateUser` (DELETE `/api/users/{id}`), `fetchRolesForSelect` (GET `/api/roles` fetching all pages for dropdown)
    - Reuse `ApiError` class and `handleResponse<T>` helper (same pattern as roles-api.ts)
    - Include `Accept-Language` header from stored locale
    - _Requirements: 10.1, 10.6, 12.1, 12.2_

  - [x] 1.4 Implement TanStack Query and Mutation hooks
    - Create `src/features/users/api/query-hooks.ts` with `userKeys` factory object and hooks: `useUser(id)` (staleTime 30s, retry 2), `useRolesForSelect()` (staleTime 60s, retry 2)
    - Create `src/features/users/api/mutation-hooks.ts` with hooks: `useCreateUser`, `useUpdateUser`, `useDeactivateUser`
    - Each mutation invalidates `userKeys.lists()` on success; `useUpdateUser` also invalidates the detail cache
    - Error handling in mutation callbacks: check for 409 (email exists) vs generic error
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 12.1, 12.2_

- [x] 2. Checkpoint - Verify API layer compiles and types are consistent
  - Ensure all tests pass, ask the user if questions arise.

- [x] 3. Shared components: PhoneInput, RoleSelect, ActiveBadge
  - [x] 3.1 Implement PhoneInput component
    - Create `src/features/users/components/PhoneInput.tsx` — styled wrapper around `react-phone-number-input`
    - Props: `value`, `onChange`, `defaultCountry` (defaults to "PL"), `error`, `disabled`
    - Style to match shadcn/ui input design tokens: dark background (#09090b), border (#27272a), rounded corners, focus ring
    - Country selector displays flags and international calling codes
    - Store value in E.164 format; format display according to selected country's national pattern
    - _Requirements: 16.1, 16.2, 16.3, 16.4, 16.6, 16.7, 16.8_

  - [x] 3.2 Implement RoleSelect component
    - Create `src/features/users/components/RoleSelect.tsx` — searchable combobox using shadcn/ui Select or Combobox
    - Props: `value`, `onChange`, `error`, `disabled`
    - Fetch roles via `useRolesForSelect()` hook; display localized role names
    - Support filtering/searching roles by name within the dropdown
    - Show error state with retry button if roles fail to load
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 15.5_

  - [x] 3.3 Implement ActiveBadge component
    - Create `src/features/users/components/ActiveBadge.tsx` — shadcn/ui Badge component
    - Props: `active: boolean`
    - Active state: green badge (success color #22c55e) with localized text "Active" / "Aktywny" / "Активен"
    - Inactive state: muted badge (muted background, muted-foreground text) with localized text "Inactive" / "Nieaktywny" / "Неактивен"
    - _Requirements: 1.9, 9.2, 15.8_

- [x] 4. Main page: UsersPage with DataTable integration
  - [x] 4.1 Implement UsersPage component with state orchestration
    - Create `src/features/users/UsersPage.tsx` — main page component managing form sheet state and deactivate dialog state
    - Define `FormSheetState` (open, mode, userId) and `DeactivateDialogState` (open, user)
    - Render page title via i18n key `users.pageTitle`
    - Render "Create User" button above the DataTable
    - Render `DataTable<UserDto>` with `entityKey="users"`, `fetchFn` adapter calling users-api, `defaultPageSize=25`, `showAuditButton={true}`
    - Define column config: name, email, roleName, active (with ActiveBadge render), and row actions (edit, deactivate)
    - Disable deactivate button for already-inactive users with tooltip
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 6.1, 6.2, 8.1, 8.2, 8.3, 9.1, 15.1, 15.6_

- [x] 5. UserFormSheet component (create/edit)
  - [x] 5.1 Implement UserFormSheet with React Hook Form + Zod integration
    - Create `src/features/users/components/UserFormSheet.tsx` — shadcn/ui Sheet overlay
    - Props: `open`, `mode`, `userId`, `onClose`, `onSuccess`
    - Use React Hook Form with `@hookform/resolvers/zod` and `userFormSchema`
    - Create mode: all fields empty, active defaults to true
    - Edit mode: fetch user via `useUser(id)`, pre-populate form, show UserFormSkeleton while loading
    - Form fields: name (text), email (text), phone (PhoneInput, default country PL), roleId (RoleSelect), locale (Select with ru/pl/en options), active (Checkbox)
    - Responsive layout: single column on mobile, two columns on desktop (name/email side by side, phone/locale side by side)
    - Validate on blur and on submit; show inline error messages below invalid fields (localized)
    - Disable submit button while mutation in progress (with spinner)
    - On success: call `onSuccess()` which closes sheet and shows toast
    - On 409 error: show email exists error toast
    - On other errors: show API error message toast
    - Cancel button closes sheet without saving
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 8.4, 11.1, 11.2, 11.3, 11.4, 11.5, 12.4, 13.2, 13.3, 15.2, 15.3_

  - [x] 5.2 Implement UserFormSkeleton
    - Create `src/features/users/components/UserFormSkeleton.tsx` — skeleton placeholders for each form field
    - Use CSS pulse animation (1.5–2s cycle) matching existing skeleton patterns
    - _Requirements: 3.7, 13.2, 13.3_

- [x] 6. DeactivateUserDialog component
  - [x] 6.1 Implement DeactivateUserDialog
    - Create `src/features/users/components/DeactivateUserDialog.tsx` — shadcn/ui AlertDialog
    - Props: `open`, `user`, `onClose`, `onSuccess`
    - Display confirmation message including user name, explain soft-delete semantics
    - On confirm: call `useDeactivateUser` mutation
    - On success: call `onSuccess()` which closes dialog and shows toast
    - On error: show error toast with API message
    - Disable confirm button while mutation is pending
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 15.4_

- [x] 7. Checkpoint - Verify CRUD flows work end-to-end with the backend
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. i18n translations and navigation integration
  - [x] 8.1 Add users namespace translations (PL and RU)
    - Add `users` key to `src/locales/pl.json` with all translation keys: page title, table column headers, form labels, button labels, dialog text, toast messages, validation messages, empty states, badge labels, locale select options
    - Add `users` key to `src/locales/ru.json` with equivalent Russian translations
    - Include specific keys: `users.pageTitle` (PL: "Zarządzanie użytkownikami", RU: "Управление пользователями"), `users.actions.create` (PL: "Utwórz użytkownika", RU: "Создать пользователя"), `users.table.name`, `users.table.email`, `users.table.role`, `users.table.active`, `users.badge.active`, `users.badge.inactive`, `users.form.name`, `users.form.email`, `users.form.phone`, `users.form.role`, `users.form.locale`, `users.form.active`, `users.validation.*`, `users.errors.emailExists`, `users.errors.network`, `users.toast.createSuccess`, `users.toast.updateSuccess`, `users.toast.deactivateSuccess`, `users.dialog.deactivateTitle`, `users.dialog.deactivateDescription`
    - _Requirements: 7.1, 7.2, 7.3, 7.5, 7.6_

  - [x] 8.2 Update router to import UsersPage from feature module
    - Update `src/app/router.tsx` to change the lazy import from `@/app/pages/UsersPage` to `@/features/users/UsersPage`
    - Update `src/app/pages/UsersPage.tsx` to re-export from the feature module (or remove if router import is changed directly)
    - Add `nav.users` key with Lucide `Users` icon to navigation config in the "System" section (if not already present)
    - Verify Top_Bar displays `users.pageTitle` on the `/users` route
    - _Requirements: 14.1, 14.2, 14.3_

- [x] 9. Testing
  - [x] 9.1 Write unit tests for Zod schema validation
    - Create `src/features/users/__tests__/user-schema.test.ts`
    - Test valid payload passes validation
    - Test name min/max length enforcement
    - Test email format validation (invalid emails rejected)
    - Test phone validation: empty string passes, valid E.164 passes, invalid number rejected
    - Test roleId required (undefined/0 fails)
    - Test locale enum enforcement (only ru/pl/en)
    - Test active defaults to true
    - _Requirements: 11.2, 16.5_

  - [x] 9.2 Write component tests for ActiveBadge and PhoneInput
    - Create `src/features/users/__tests__/ActiveBadge.test.tsx`
    - Test renders green badge with "Active" text when active=true
    - Test renders muted badge with "Inactive" text when active=false
    - Create `src/features/users/__tests__/PhoneInput.test.tsx`
    - Test renders with default country PL
    - Test calls onChange with E.164 formatted value
    - Test shows error styling when error=true
    - _Requirements: 1.9, 9.2, 16.1, 16.2_

  - [x] 9.3 Write component tests for UserFormSheet
    - Create `src/features/users/__tests__/UserFormSheet.test.tsx`
    - Test create mode renders empty fields
    - Test edit mode fetches user and pre-populates form (use MSW mock)
    - Test validation errors displayed inline on invalid submit
    - Test submit button disabled while mutation pending
    - Test successful create calls POST and triggers onSuccess
    - Test 409 error shows email exists toast
    - _Requirements: 2.1, 2.5, 2.7, 3.1, 3.4, 11.3, 11.5, 12.2_

  - [x] 9.4 Write component tests for DeactivateUserDialog
    - Create `src/features/users/__tests__/DeactivateUserDialog.test.tsx`
    - Test renders user name in confirmation message
    - Test confirm calls DELETE mutation
    - Test success triggers onSuccess callback
    - Test error shows error toast
    - _Requirements: 4.1, 4.3, 4.4, 4.5_

  - [x] 9.5 Write integration tests for UsersPage
    - Create `src/features/users/__tests__/UsersPage.test.tsx`
    - Test page renders DataTable with users data (MSW mock GET /api/users)
    - Test "Create User" button opens form sheet
    - Test edit action on row opens form sheet in edit mode
    - Test deactivate action opens confirmation dialog
    - Test full create flow end-to-end (fill form → submit → toast → sheet closes)
    - _Requirements: 1.1, 1.2, 1.8, 2.1, 3.1, 4.1_

- [x] 10. Final checkpoint - Full integration verification
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- The design does NOT include Correctness Properties (this is a UI module), so no property-based tests are included — only example-based unit/component tests with Vitest + Testing Library + MSW
- The backend is already complete (UserController with full CRUD) — this is a frontend-only implementation
- The DataTable component at `src/components/data-table/` handles search, sort, filter, pagination, skeleton loading, empty states, and audit button automatically
- The `fetchFn` adapter in UsersPage bridges DataTable's `FetchParams` to the users API (same pattern as RolesListTab)
- Phone validation uses `libphonenumber-js` — the field is optional but non-empty values must be valid E.164
- The Role select dropdown fetches from `/api/roles` and displays locale-resolved role names
- Audit integration is provided automatically by DataTable with `showAuditButton={true}` — no custom implementation needed

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "1.3"] },
    { "id": 2, "tasks": ["1.4", "3.1", "3.2", "3.3"] },
    { "id": 3, "tasks": ["4.1"] },
    { "id": 4, "tasks": ["5.1", "5.2", "6.1"] },
    { "id": 5, "tasks": ["8.1", "8.2"] },
    { "id": 6, "tasks": ["9.1", "9.2"] },
    { "id": 7, "tasks": ["9.3", "9.4", "9.5"] }
  ]
}
```
