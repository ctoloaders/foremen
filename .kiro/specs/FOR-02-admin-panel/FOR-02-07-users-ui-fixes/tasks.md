# Implementation Plan

## Phase 1: Bug Condition Exploration Tests (BEFORE fix)

- [-] 1. Write bug condition exploration tests for backend bugs
  - **Property 1: Bug Condition** - Backend Validation & Persistence Bugs (BUG 1.4, 1.6, 1.7)
  - **CRITICAL**: These tests MUST FAIL on unfixed code — failure confirms the bugs exist
  - **DO NOT attempt to fix the tests or the code when they fail**
  - **NOTE**: These tests encode the expected behavior — they will validate the fix when they pass after implementation
  - **GOAL**: Surface counterexamples that demonstrate the bugs exist
  - **Scoped PBT Approach**:
    - BUG 1.4: Property that `validateLocale("en")` throws `ForemenApiException` with BAD_REQUEST — currently passes silently
    - BUG 1.6: Property that `resolveRole(adminRoleId)` throws `ForemenApiException` with CONFLICT when role.code == "ADMIN" — currently allows it
    - BUG 1.7: Property that for any non-null `Map<String, Object>`, `JsonMapConverter.convertToDatabaseColumn(map)` returns a `PGobject` with type "jsonb" — currently returns String
  - Test file: `foremen-backend/src/test/java/com/foremen/service/UserServiceBugConditionTest.java` (jqwik)
  - Test file: `foremen-backend/src/test/java/com/foremen/config/persistence/JsonMapConverterBugConditionTest.java` (jqwik)
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests FAIL (this is correct — it proves the bugs exist)
  - Document counterexamples:
    - BUG 1.4: `validateLocale("en")` does NOT throw — "en" is accepted
    - BUG 1.6: `resolveRole(adminRoleId)` returns ADMIN role entity without error
    - BUG 1.7: `convertToDatabaseColumn({"theme":"dark"})` returns `String` not `PGobject`
  - Mark task complete when tests are written, run, and failure is documented
  - _Requirements: 1.4, 1.6, 1.7_

- [~] 2. Write bug condition exploration tests for frontend bugs
  - **Property 1: Bug Condition** - Frontend Rendering & Architecture Bugs (BUG 1.1, 1.2, 1.3, 1.5)
  - **CRITICAL**: These tests MUST FAIL on unfixed code — failure confirms the bugs exist
  - **DO NOT attempt to fix the tests or the code when they fail**
  - **NOTE**: These tests encode the expected behavior — they will validate the fix when they pass after implementation
  - **GOAL**: Surface counterexamples that demonstrate the bugs exist
  - **Scoped PBT Approach**:
    - BUG 1.1: Test that RoleSelect PopoverContent has `bg-popover` class — currently missing
    - BUG 1.2: Test that opening RoleSelect triggers exactly 1 fetch (page=0, size=20) — currently fetches ALL pages in a loop
    - BUG 1.3: Test that typing in search input triggers a fetch to `/api/roles?query=name~ct~{input}` — currently no server request
    - BUG 1.5: Test that UserFormSheet locale Select does NOT render a SelectItem with value "en" — currently renders it
  - Test file: `foremen-frontend/src/features/users/__tests__/bug-condition.test.tsx` (Vitest + Testing Library)
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests FAIL (this is correct — it proves the bugs exist)
  - Document counterexamples:
    - BUG 1.1: PopoverContent lacks `bg-popover` class
    - BUG 1.2: `fetchRolesForSelect()` makes N fetch calls (one per page) instead of 1
    - BUG 1.3: No network request made when search input changes
    - BUG 1.5: SelectItem with value="en" is rendered in the DOM
  - Mark task complete when tests are written, run, and failure is documented
  - _Requirements: 1.1, 1.2, 1.3, 1.5_

## Phase 2: Preservation Property Tests (BEFORE fix)

- [~] 3. Write preservation property tests for backend
  - **Property 2: Preservation** - Backend Valid Behavior Unchanged
  - **IMPORTANT**: Follow observation-first methodology
  - Observe on UNFIXED code:
    - `validateLocale("ru")` passes without error
    - `validateLocale("pl")` passes without error
    - `resolveRole(clientRoleId)` returns role entity (code != "ADMIN")
    - `JsonMapConverter.convertToDatabaseColumn(null)` returns null
    - `JsonMapConverter.convertToEntityAttribute(null)` returns null
    - `JsonMapConverter` round-trips any valid JSON map correctly (serialize → deserialize = identity)
  - Write property-based tests (jqwik):
    - For all locale in {"ru", "pl"}: `validateLocale(locale)` does not throw
    - For all roles where code ≠ "ADMIN": `resolveRole(roleId)` returns the role without error
    - For all null inputs: converter returns null
    - For all non-null `Map<String, Object>` with valid JSON values: round-trip preserves equality
  - Test file: `foremen-backend/src/test/java/com/foremen/service/UserServicePreservationTest.java`
  - Test file: `foremen-backend/src/test/java/com/foremen/config/persistence/JsonMapConverterPreservationTest.java`
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests PASS (this confirms baseline behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.2, 3.3, 3.4, 3.5_

- [~] 4. Write preservation property tests for frontend
  - **Property 2: Preservation** - Frontend Valid Behavior Unchanged
  - **IMPORTANT**: Follow observation-first methodology
  - Observe on UNFIXED code:
    - RoleSelect renders correctly in light theme (no visual issues)
    - UserFormSheet locale Select renders "Polski (PL)" and "Русский (RU)" options
    - Zod schema accepts locale "ru" and "pl" without validation error
    - RoleSelect open/select/close interaction works correctly
    - Closing and re-opening RoleSelect resets search state
  - Write tests (Vitest + Testing Library):
    - Verify light theme rendering is correct (no `bg-popover` regression)
    - Verify "Polski (PL)" and "Русский (RU)" options present in locale Select
    - Verify Zod schema passes for locale "ru" and "pl"
    - Verify RoleSelect interaction: open → select → close → value updates
    - Verify RoleSelect search state resets on close/reopen
  - Test file: `foremen-frontend/src/features/users/__tests__/preservation.test.tsx`
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests PASS (this confirms baseline behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.6, 3.7_

## Phase 3: Implementation

- [ ] 5. Fix BUG 1.1 — Add bg-popover class to PopoverContent in RoleSelect

  - [~] 5.1 Apply the fix
    - Add `bg-popover` class to PopoverContent in `RoleSelect.tsx`
    - Change: `className="w-[var(--radix-popover-trigger-width)] p-0"` → `className="w-[var(--radix-popover-trigger-width)] bg-popover p-0"`
    - _Bug_Condition: input.action == "openRoleSelect" AND input.theme == "dark"_
    - _Expected_Behavior: PopoverContent has opaque background via bg-popover class_
    - _Preservation: Light theme rendering unchanged_
    - _Requirements: 2.1, 3.1_

- [ ] 6. Fix BUG 1.2 — Replace fetch-all loop with paginated fetchRolesPage()

  - [~] 6.1 Implement paginated API function
    - Replace `fetchRolesForSelect()` in `users-api.ts` with `fetchRolesPage({ page, size, query? })` that fetches a single page
    - Keep `fetchRolesForSelect()` signature temporarily for backward compat if needed, or remove entirely
    - Add `PaginatedResponse<RoleOption>` as return type for single-page fetch
    - _Bug_Condition: input.action == "openRoleSelect" AND roleCount > 0_
    - _Expected_Behavior: Only first page (size=20) fetched initially_
    - _Preservation: Correct role data returned, just paginated differently_
    - _Requirements: 2.2, 3.6_

  - [~] 6.2 Implement useRolesInfinite hook
    - Replace `useRolesForSelect()` in `query-hooks.ts` with `useRolesInfinite(search?: string)` using TanStack Query `useInfiniteQuery`
    - queryKey: `[...userKeys.roles(), { search }]`
    - queryFn: `({ pageParam = 0 }) => fetchRolesPage({ page: pageParam, size: 20, query: search ? \`name~ct~${search}\` : undefined })`
    - getNextPageParam: `(lastPage) => lastPage.last ? undefined : lastPage.number + 1`
    - staleTime: 60_000
    - _Requirements: 2.2, 2.3_

- [ ] 7. Fix BUG 1.3 — Rewrite RoleSelect with server-side search and infinite scroll

  - [~] 7.1 Implement debounced server-side search + IntersectionObserver infinite scroll
    - Add `useDebounce(search, 300)` hook (custom or from a utility) for 300ms debounce
    - Replace `useRolesForSelect()` with `useRolesInfinite(debouncedSearch)` in RoleSelect
    - Remove client-side `filteredRoles` useMemo — data comes from infinite query pages
    - Flatten pages: `data?.pages.flatMap(p => p.content) ?? []`
    - Add scroll sentinel `<div ref={sentinelRef} />` at bottom of the list
    - Use IntersectionObserver on sentinel to call `fetchNextPage()` when visible and `hasNextPage` is true
    - Show loading spinner when `isFetchingNextPage` is true
    - Reset search state when popover closes (`onOpenChange`)
    - _Bug_Condition: input.action == "searchRole" AND input.searchQuery != ""_
    - _Expected_Behavior: Debounced server request with query=name~ct~{input}, paginated infinite scroll results_
    - _Preservation: Mouse/keyboard interaction patterns unchanged, scrolling without search shows all roles in paginated order, re-opening resets search_
    - _Requirements: 2.2, 2.3, 3.6, 3.7_

- [ ] 8. Fix BUG 1.4 — Remove "en" from SUPPORTED_LOCALES in UserService.java

  - [~] 8.1 Apply the fix
    - Change `Set.of("ru", "pl", "en")` to `Set.of("ru", "pl")` in `UserService.java`
    - _Bug_Condition: input.action == "submitUserForm" AND input.locale == "en"_
    - _Expected_Behavior: validateLocale("en") throws ForemenApiException with BAD_REQUEST_
    - _Preservation: validateLocale("ru") and validateLocale("pl") continue to pass_
    - _Requirements: 2.4, 3.2_

- [ ] 9. Fix BUG 1.5 — Remove English locale option from frontend

  - [~] 9.1 Apply the fix
    - In `user-schema.ts`: change `z.enum(['ru', 'pl', 'en'], ...)` to `z.enum(['ru', 'pl'], ...)`
    - In `UserFormSheet.tsx`: remove `<SelectItem value="en">English (EN)</SelectItem>`
    - In `UserFormSheet.tsx`: change `as 'ru' | 'pl' | 'en'` to `as 'ru' | 'pl'` in edit mode reset
    - _Bug_Condition: input.action == "renderUserForm" (always shows English option)_
    - _Expected_Behavior: Only "Polski (PL)" and "Русский (RU)" options displayed, Zod rejects "en"_
    - _Preservation: Valid locale values "ru" and "pl" continue to work in form and schema_
    - _Requirements: 2.5, 3.2_

- [ ] 10. Fix BUG 1.6 — Add admin role check in resolveRole()

  - [~] 10.1 Apply the fix
    - Add admin role check after `findById` in `resolveRole()` method of `UserService.java`:
      ```java
      if ("ADMIN".equals(role.getCode())) {
          throw new ForemenApiException(HttpStatus.CONFLICT, "error.user.admin.role.prohibited");
      }
      ```
    - _Bug_Condition: input.action == "assignRole" AND resolvedRole.code == "ADMIN"_
    - _Expected_Behavior: HTTP 409 Conflict with error key "error.user.admin.role.prohibited"_
    - _Preservation: Non-admin roles (CLIENT, MANAGER, etc.) continue to be assigned without restriction_
    - _Requirements: 2.6, 3.3_

- [ ] 11. Fix BUG 1.7 — Change JsonMapConverter to return PGobject with type "jsonb"

  - [~] 11.1 Apply the fix
    - Change `AttributeConverter<Map<String, Object>, String>` to `AttributeConverter<Map<String, Object>, Object>`
    - In `convertToDatabaseColumn()`: return `PGobject` with `setType("jsonb")` and `setValue(json)` instead of raw String
    - In `convertToEntityAttribute()`: handle both `PGobject` (extract `.getValue()`) and `String` (direct parse) inputs
    - Add import: `import org.postgresql.util.PGobject;`
    - _Bug_Condition: input.action == "createUser" AND input.displayPreferences != null_
    - _Expected_Behavior: PGobject with type "jsonb" sent to PostgreSQL, no type mismatch error_
    - _Preservation: null displayPreferences continues to persist as null, round-trip serialization preserved_
    - _Requirements: 2.7, 3.4_

## Phase 4: Verify Fixes

- [ ] 12. Verify bug condition exploration tests now pass

  - [~] 12.1 Verify backend bug condition tests pass
    - **Property 1: Expected Behavior** - Backend Validation & Persistence Fixed
    - **IMPORTANT**: Re-run the SAME tests from task 1 — do NOT write new tests
    - The tests from task 1 encode the expected behavior
    - When these tests pass, it confirms the expected behavior is satisfied
    - Run backend bug condition exploration tests from step 1
    - **EXPECTED OUTCOME**: Tests PASS (confirms bugs 1.4, 1.6, 1.7 are fixed)
    - _Requirements: 2.4, 2.6, 2.7_

  - [~] 12.2 Verify frontend bug condition tests pass
    - **Property 1: Expected Behavior** - Frontend Rendering & Architecture Fixed
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - The tests from task 2 encode the expected behavior
    - When these tests pass, it confirms the expected behavior is satisfied
    - Run frontend bug condition exploration tests from step 2
    - **EXPECTED OUTCOME**: Tests PASS (confirms bugs 1.1, 1.2, 1.3, 1.5 are fixed)
    - _Requirements: 2.1, 2.2, 2.3, 2.5_

- [ ] 13. Verify preservation tests still pass

  - [~] 13.1 Verify backend preservation tests still pass
    - **Property 2: Preservation** - Backend Valid Behavior Still Unchanged
    - **IMPORTANT**: Re-run the SAME tests from task 3 — do NOT write new tests
    - Run preservation property tests from step 3
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions in backend)
    - Confirm locale "ru"/"pl" accepted, non-admin roles assigned, null preferences handled, converter round-trips
    - _Requirements: 3.2, 3.3, 3.4, 3.5_

  - [~] 13.2 Verify frontend preservation tests still pass
    - **Property 2: Preservation** - Frontend Valid Behavior Still Unchanged
    - **IMPORTANT**: Re-run the SAME tests from task 4 — do NOT write new tests
    - Run preservation property tests from step 4
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions in frontend)
    - Confirm light theme works, valid locales rendered, interactions preserved
    - _Requirements: 3.1, 3.2, 3.6, 3.7_

## Phase 5: Checkpoint

- [~] 14. Checkpoint — Ensure all tests pass
  - Run full backend test suite: `./gradlew test` in `foremen-backend/`
  - Run full frontend test suite: `npx vitest --run` in `foremen-frontend/`
  - Ensure all bug condition tests pass (bugs are fixed)
  - Ensure all preservation tests pass (no regressions)
  - Ensure no compilation errors in either project
  - Ask the user if questions arise
