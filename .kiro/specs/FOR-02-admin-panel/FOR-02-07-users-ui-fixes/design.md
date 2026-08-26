# FOR-02-07-users-ui-fixes Bugfix Design

## Overview

This design addresses 7 bugs in the Users management UI spanning frontend rendering (dark theme), frontend architecture (roles fetching/search), locale validation consistency (frontend + backend), backend security (admin role assignment), and JDBC/PostgreSQL type mismatch (jsonb persistence). The fix approach is minimal and targeted: each bug has an isolated root cause and a single-point fix that does not affect unrelated behaviors.

## Glossary

- **Bug_Condition (C)**: The set of conditions under which any of the 7 bugs manifest — transparent popover in dark mode, fetch-all-pages loop, client-side-only filtering, "en" locale accepted, English option displayed, admin role assignable, jsonb type mismatch
- **Property (P)**: The desired correct behavior for each bug condition — opaque background, paginated fetch, server-side search, locale restricted to ru/pl, no English option, admin role rejected, PGobject with jsonb type
- **Preservation**: Existing correct behaviors that must remain unchanged — light theme rendering, valid locale acceptance, non-admin role assignment, null displayPreferences handling, general user CRUD flow
- **RoleSelect**: The React component in `RoleSelect.tsx` that renders a popover-based dropdown for selecting a user's role
- **UserFormSheet**: The React component in `UserFormSheet.tsx` that renders the create/edit user form in a side sheet
- **fetchRolesForSelect()**: The API function in `users-api.ts` that currently fetches all role pages in a loop
- **UserService**: The Spring service in `UserService.java` that handles user create/update validation and persistence
- **JsonMapConverter**: The JPA `AttributeConverter` in `JsonMapConverter.java` that serializes `Map<String, Object>` to JSON for PostgreSQL's jsonb column

## Bug Details

### Bug Condition

The bugs manifest across 7 independent conditions. A user encounters a bug when ANY of the following holds:

**Formal Specification:**
```
FUNCTION isBugCondition(input)
  INPUT: input of type UserUIInteraction
  OUTPUT: boolean

  RETURN
    (input.action == "openRoleSelect" AND input.theme == "dark")
    OR (input.action == "openRoleSelect" AND roleCount > 0)
    OR (input.action == "searchRole" AND input.searchQuery != "")
    OR (input.action == "submitUserForm" AND input.locale == "en")
    OR (input.action == "renderUserForm")  // always shows "English (EN)"
    OR (input.action == "assignRole" AND resolvedRole.code == "ADMIN")
    OR (input.action == "createUser" AND input.displayPreferences != null)
END FUNCTION
```

### Examples

- **BUG 1.1**: User opens RoleSelect in dark mode → PopoverContent has transparent background, text overlaps page content
- **BUG 1.2**: System has 150 roles → `fetchRolesForSelect()` makes 2 requests (page 0 size=100, page 1 size=100) before rendering dropdown
- **BUG 1.3**: User types "manager" in role search → only filters the already-fetched client-side array, misses roles not yet loaded
- **BUG 1.4**: User submits form with locale "en" → backend accepts it (SUPPORTED_LOCALES includes "en"), but no English translations exist
- **BUG 1.5**: User opens locale dropdown → sees "English (EN)" as a selectable option alongside PL and RU
- **BUG 1.6**: User assigns role with code "ADMIN" to another user → backend allows it, escalating privileges
- **BUG 1.7**: User is created with `displayPreferences: { theme: "dark" }` → Hibernate sends VARCHAR, PostgreSQL expects jsonb → SQL error

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Light theme rendering of RoleSelect and all other components must remain identical
- Valid locale values "ru" and "pl" must continue to be accepted by both frontend and backend
- Non-admin role assignment (e.g., CLIENT, MANAGER roles) must continue to work without restriction
- Null or empty displayPreferences must continue to persist as null without error
- Email uniqueness validation, phone validation, role resolution, and general CRUD flow must remain unchanged
- Mouse/keyboard interaction patterns in the RoleSelect (open, select, close) must remain the same
- Scrolling the role list without searching must display all roles in paginated natural order
- Re-opening the dropdown must reset search state

**Scope:**
All inputs that do NOT match the bug conditions above should be completely unaffected. This includes:
- Any user interaction in light theme (BUG 1.1 fix only adds dark-mode background)
- Role fetching still returns correct data (just paginated differently)
- Form submissions with valid locales "ru" or "pl"
- Role assignment for any non-ADMIN role
- User creation with null displayPreferences

## Hypothesized Root Cause

Based on the bug analysis and source code inspection:

1. **BUG 1.1 — Missing background class**: `PopoverContent` in `RoleSelect.tsx` has no explicit background class. Shadcn/Radix PopoverContent normally inherits `bg-popover` from the component definition, but the custom styling with `className="w-[var(--radix-popover-trigger-width)] p-0"` may override defaults. The inner `<div>` also lacks a background class.

2. **BUG 1.2 — Fetch-all loop**: `fetchRolesForSelect()` in `users-api.ts` uses a `while (page < totalPages)` loop fetching all pages with size=100. This is an architectural choice that doesn't scale.

3. **BUG 1.3 — Client-side filtering only**: `RoleSelect.tsx` uses `useMemo` with `roles.filter()` on the search query — purely client-side. No server request is made when the user types.

4. **BUG 1.4 — "en" in SUPPORTED_LOCALES**: `UserService.java` line `Set.of("ru", "pl", "en")` includes "en" despite no English translations existing in the application.

5. **BUG 1.5 — English SelectItem**: `UserFormSheet.tsx` renders `<SelectItem value="en">English (EN)</SelectItem>` and `user-schema.ts` includes "en" in the Zod enum.

6. **BUG 1.6 — No admin-role check**: `resolveRole()` in `UserService.java` only checks existence (`findById`) but never validates that the resolved role's `code` is not "ADMIN".

7. **BUG 1.7 — String return type**: `JsonMapConverter.convertToDatabaseColumn()` returns `String`. PostgreSQL's JDBC driver sends this as VARCHAR, but the column is typed `jsonb`. The driver needs a `PGobject` with explicit type or `@JdbcTypeCode(SqlTypes.JSON)` annotation.

## Correctness Properties

Property 1: Bug Condition — Dark Theme Popover Background

_For any_ RoleSelect rendering where the active theme is "dark", the PopoverContent SHALL have an opaque background (via `bg-popover` class) ensuring dropdown options are readable against the page content.

**Validates: Requirements 2.1**

Property 2: Bug Condition — Paginated Role Fetching

_For any_ opening of the RoleSelect dropdown, the system SHALL fetch only the first page of roles (size ≤ 20) and load subsequent pages only when the user scrolls to the bottom of the list (infinite scroll).

**Validates: Requirements 2.2**

Property 3: Bug Condition — Server-Side Role Search

_For any_ search input in the RoleSelect where the debounced query is non-empty, the system SHALL send a server-side request with `query=name~ct~{input}` parameter and display paginated results with infinite scroll, NOT filter client-side.

**Validates: Requirements 2.3**

Property 4: Bug Condition — Locale Validation Restriction

_For any_ user create/update request with locale value "en", both the frontend schema validation AND backend `validateLocale()` SHALL reject the value (frontend: Zod enum excludes "en"; backend: SUPPORTED_LOCALES excludes "en").

**Validates: Requirements 2.4**

Property 5: Bug Condition — No English Locale Option

_For any_ rendering of the UserFormSheet locale selector, the system SHALL NOT display an "English (EN)" option — only "Polski (PL)" and "Русский (RU)" SHALL be present.

**Validates: Requirements 2.5**

Property 6: Bug Condition — Admin Role Assignment Blocked

_For any_ user create/update request where the resolved role has code "ADMIN", the system SHALL reject with HTTP 409 Conflict and error key "error.user.admin.role.prohibited".

**Validates: Requirements 2.6**

Property 7: Bug Condition — JSONB Persistence Type

_For any_ user create/update with a non-null `displayPreferences` map, the `JsonMapConverter.convertToDatabaseColumn()` SHALL return a `PGobject` with type "jsonb" (or use `@JdbcTypeCode(SqlTypes.JSON)` annotation) so PostgreSQL receives the correct type.

**Validates: Requirements 2.7**

Property 8: Preservation — Existing Behaviors Unchanged

_For any_ input where none of the bug conditions hold (light theme popover, valid locale "ru"/"pl", non-admin role, null displayPreferences, non-search role browsing), the fixed system SHALL produce the same result as the original system, preserving all existing functionality.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7**

## Fix Implementation

### Changes Required

#### BUG 1.1 — Dark Theme Popover Background

**File**: `foremen-frontend/src/features/users/components/RoleSelect.tsx`

**Specific Changes**:
1. Add `bg-popover` class to the `<PopoverContent>` element:
   ```tsx
   <PopoverContent className="w-[var(--radix-popover-trigger-width)] bg-popover p-0" align="start">
   ```

---

#### BUG 1.2 — Paginated Role Fetching (Infinite Scroll)

**File**: `foremen-frontend/src/features/users/api/users-api.ts`

**Specific Changes**:
1. **Replace `fetchRolesForSelect()`** with a `fetchRolesPage(page, size, query?)` function that fetches a single page:
   ```ts
   export async function fetchRolesPage(params: { page: number; size: number; query?: string }): Promise<PaginatedResponse<RoleOption>> {
     const searchParams = new URLSearchParams()
     searchParams.set('page', String(params.page))
     searchParams.set('size', String(params.size))
     if (params.query) searchParams.set('query', params.query)
     const response = await fetch(`${BASE_URL}/roles?${searchParams}`, { headers: getHeaders() })
     return handleResponse<PaginatedResponse<RoleOption>>(response)
   }
   ```

**File**: `foremen-frontend/src/features/users/api/query-hooks.ts`

**Specific Changes**:
1. **Replace `useRolesForSelect()`** with `useRolesInfinite(query?)` using TanStack Query's `useInfiniteQuery`:
   ```ts
   export function useRolesInfinite(search?: string) {
     return useInfiniteQuery({
       queryKey: [...userKeys.roles(), { search }],
       queryFn: ({ pageParam = 0 }) => fetchRolesPage({ page: pageParam, size: 20, query: search ? `name~ct~${search}` : undefined }),
       getNextPageParam: (lastPage) => lastPage.last ? undefined : lastPage.number + 1,
       staleTime: 60_000,
     })
   }
   ```

---

#### BUG 1.3 — Server-Side Search with Debounce

**File**: `foremen-frontend/src/features/users/components/RoleSelect.tsx`

**Specific Changes**:
1. **Add debounced search state** (300ms) that triggers server-side query via `useRolesInfinite(debouncedSearch)`
2. **Remove client-side `filteredRoles` useMemo** — replace with data from infinite query pages
3. **Add scroll sentinel** at the bottom of the list to trigger `fetchNextPage()` when scrolled into view (IntersectionObserver or scroll event)
4. **Reset search** when popover closes

---

#### BUG 1.4 — Remove "en" from Backend SUPPORTED_LOCALES

**File**: `foremen-backend/src/main/java/com/foremen/service/UserService.java`

**Specific Changes**:
1. Change line:
   ```java
   private static final Set<String> SUPPORTED_LOCALES = Set.of("ru", "pl", "en");
   ```
   To:
   ```java
   private static final Set<String> SUPPORTED_LOCALES = Set.of("ru", "pl");
   ```

---

#### BUG 1.5 — Remove English Locale Option from Frontend

**File**: `foremen-frontend/src/features/users/schemas/user-schema.ts`

**Specific Changes**:
1. Change Zod enum from `z.enum(['ru', 'pl', 'en'], ...)` to `z.enum(['ru', 'pl'], ...)`

**File**: `foremen-frontend/src/features/users/components/UserFormSheet.tsx`

**Specific Changes**:
1. Remove the line: `<SelectItem value="en">English (EN)</SelectItem>`
2. Update the type cast in edit mode from `as 'ru' | 'pl' | 'en'` to `as 'ru' | 'pl'`

---

#### BUG 1.6 — Block Admin Role Assignment

**File**: `foremen-backend/src/main/java/com/foremen/service/UserService.java`

**Specific Changes**:
1. Add validation after `resolveRole()` in both `create()` and `update()` methods:
   ```java
   private RoleEntity resolveRole(Long roleId) {
       if (roleId == null) {
           throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.user.role.required");
       }
       RoleEntity role = roleDao.findById(roleId)
               .orElseThrow(() -> new ForemenApiException(HttpStatus.BAD_REQUEST, "error.user.role.not.found", roleId));
       if ("ADMIN".equals(role.getCode())) {
           throw new ForemenApiException(HttpStatus.CONFLICT, "error.user.admin.role.prohibited");
       }
       return role;
   }
   ```

---

#### BUG 1.7 — Fix JSONB Persistence Type

**File**: `foremen-backend/src/main/java/com/foremen/config/persistence/JsonMapConverter.java`

**Specific Changes**:
1. Change the converter's database column type from `String` to `Object` and return a `PGobject`:
   ```java
   @Converter(autoApply = false)
   public class JsonMapConverter implements AttributeConverter<Map<String, Object>, Object> {

       @Override
       public Object convertToDatabaseColumn(Map<String, Object> attribute) {
           if (attribute == null) return null;
           try {
               PGobject pgObject = new PGobject();
               pgObject.setType("jsonb");
               pgObject.setValue(MAPPER.writeValueAsString(attribute));
               return pgObject;
           } catch (Exception e) {
               throw new IllegalArgumentException("Cannot serialize display_preferences to JSON", e);
           }
       }

       @Override
       public Map<String, Object> convertToEntityAttribute(Object dbData) {
           if (dbData == null) return null;
           try {
               String json = dbData instanceof PGobject ? ((PGobject) dbData).getValue() : dbData.toString();
               return MAPPER.readValue(json, MAP_TYPE_REF);
           } catch (Exception e) {
               throw new IllegalArgumentException("Cannot deserialize display_preferences from JSON", e);
           }
       }
   }
   ```
2. Add import: `import org.postgresql.util.PGobject;`

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate each bug on unfixed code, then verify the fix works correctly and preserves existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bugs BEFORE implementing the fixes. Confirm or refute the root cause analysis.

**Test Plan**: Write targeted tests for each bug that will fail on unfixed code.

**Test Cases**:
1. **Dark Theme Popover Test**: Render RoleSelect with dark class on `<html>`, open popover, assert `bg-popover` class exists (will fail on unfixed code)
2. **Fetch-All Loop Test**: Mock `/api/roles` with 3 pages, call `fetchRolesForSelect()`, assert only 1 fetch call was made (will fail — currently makes 3)
3. **Server-Side Search Test**: Type "manager" in RoleSelect search, assert a fetch to `/api/roles?query=name~ct~manager` was made (will fail — currently no fetch)
4. **Backend Locale "en" Test**: Call `UserService.create()` with locale "en", assert it throws (will fail — currently accepts "en")
5. **English Option Render Test**: Render UserFormSheet, assert no SelectItem with value "en" exists (will fail — currently renders it)
6. **Admin Role Assignment Test**: Call `UserService.create()` with roleId pointing to ADMIN role, assert HTTP 409 (will fail — currently allows it)
7. **JSONB Persistence Test**: Create user with non-null displayPreferences, assert no SQL type mismatch error (will fail on unfixed code with PostgreSQL)

**Expected Counterexamples**:
- Tests 1, 2, 3, 5 fail due to incorrect/missing DOM elements or excessive network calls
- Tests 4, 6 fail because validation is too permissive
- Test 7 fails with `PSQLException: column "display_preferences" is of type jsonb but expression is of type character varying`

### Fix Checking

**Goal**: Verify that for all inputs where any bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**
```
FOR ALL input WHERE isBugCondition(input) DO
  result := fixedSystem(input)
  ASSERT expectedBehavior(result)
END FOR
```

Specifically:
- For BUG 1.1: Assert PopoverContent has opaque background in dark theme
- For BUG 1.2: Assert only one page fetched initially, next page fetched on scroll
- For BUG 1.3: Assert server request made with debounced query
- For BUG 1.4: Assert locale "en" rejected by backend with BAD_REQUEST
- For BUG 1.5: Assert no "English (EN)" option rendered
- For BUG 1.6: Assert admin role rejected with HTTP 409
- For BUG 1.7: Assert user with displayPreferences persists without error

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed system produces the same result as the original.

**Pseudocode:**
```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT originalSystem(input) = fixedSystem(input)
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:
- It generates many test cases automatically across the input domain
- It catches edge cases that manual unit tests might miss
- It provides strong guarantees that behavior is unchanged for all non-buggy inputs

**Test Plan**: Observe behavior on UNFIXED code first for valid inputs, then write property-based tests capturing that behavior.

**Test Cases**:
1. **Light Theme Preservation**: Verify RoleSelect renders correctly in light theme after BUG 1.1 fix
2. **Valid Locale Preservation**: For any locale in {"ru", "pl"}, verify backend accepts and persists correctly
3. **Non-Admin Role Preservation**: For any role where code ≠ "ADMIN", verify assignment succeeds
4. **Null DisplayPreferences Preservation**: Verify user with null displayPreferences persists without error
5. **General CRUD Preservation**: Verify full create/update/deactivate flow continues to work with valid data
6. **Paginated Browse Preservation**: Verify scrolling without search still shows all roles in order
7. **Dropdown Reset Preservation**: Verify closing and re-opening dropdown resets search state

### Unit Tests

- Test `JsonMapConverter.convertToDatabaseColumn()` returns PGobject with type "jsonb"
- Test `JsonMapConverter.convertToEntityAttribute()` handles both PGobject and String inputs
- Test `UserService.validateLocale()` rejects "en" and accepts "ru", "pl"
- Test `UserService.resolveRole()` throws CONFLICT for ADMIN role code
- Test Zod schema rejects locale "en" and accepts "ru", "pl"
- Test RoleSelect renders `bg-popover` class on PopoverContent
- Test UserFormSheet does not render English locale option

### Property-Based Tests

- Generate random locale strings and verify only "ru" and "pl" pass both frontend and backend validation (Property 4)
- Generate random role codes and verify only "ADMIN" is rejected, all others pass (Property 6)
- Generate random `Map<String, Object>` instances and verify JsonMapConverter round-trips correctly (Property 7)
- Generate random search queries and verify debounced server request is made with correct format (Property 3)

### Integration Tests

- Test full user creation flow with valid data (locale "pl", non-admin role, displayPreferences)
- Test user creation rejected when locale is "en" (frontend validation prevents submission)
- Test user creation rejected when admin role selected (backend returns 409)
- Test RoleSelect infinite scroll loads pages correctly with and without search
- Test theme switching does not break RoleSelect visual rendering
