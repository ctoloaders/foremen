# Implementation Plan — FOR-04-13: Project entity + project-scoped anchor + custom create + Google Places proxy

## Overview

Convert the FOR-04-13 design into incremental, test-driven coding steps. Order: backend foundation
(entity/enum/table) → DAO/service/custom-create orchestrator/controllers → filtering & metadata →
ABAC seed → frontend feature → tests → test-cases.md. Each step builds on the previous ones and wires
into the running application with no orphaned code. Backend is Java (Spring Boot / JPA / Liquibase /
jqwik / Testcontainers); frontend is TypeScript/React (Vitest + RTL). Per the workspace test-execution
standard, run only the affected test classes with `--tests` and read the JUnit XML for pass/fail;
never run the full suite unless explicitly requested.

## Tasks

- [x] 1. Backend foundation: entity, status enum, read-only members association, table changeset
  - [x] 1.1 Create `ProjectStatus` enum
    - Define exactly `DRAFT, ACTIVE, ON_HOLD, COMPLETED, CANCELLED` in that declaration order and no other values
    - _Requirements: 1.3_
  - [x] 1.2 Write `ProjectStatusEnumTest`
    - Assert `values()` equals the five values in declaration order
    - _Requirements: 1.3_
  - [x] 1.3 Create `ProjectEntity extends BaseEntity`
    - Map `projects` table with descriptive columns only: `name` (NOT NULL, 255), `address` (500), `googlePlaceId`→`google_place_id` (255), `formattedAddress`→`formatted_address` (500), `latitude`/`longitude` (`NUMERIC(10,7)`), `area` (`NUMERIC(12,2)`), `startDate`→`start_date`, `endDate`→`end_date`, `status` (`@Enumerated(EnumType.STRING)`, nullable=false, default `DRAFT`)
    - No `client`/`manager` field, no `@ManyToOne UserEntity`
    - Add read-only `@OneToMany members` mapped by `project_id` (`@JoinColumn insertable=false, updatable=false`), `FetchType.LAZY`, `@BatchSize(size=100)`; never mutated through the entity
    - _Requirements: 1.1, 1.2, 1.5_
  - [x] 1.4 Create Liquibase `040-create-projects.xml` and register it in `changelog.xml`
    - Create `projects` table with all columns from Data Models (incl. `status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'` and the four `created_date`/`created_by`/`updated_date`/`updated_by` audit columns); no `client_id`/`manager_id`/users FK
    - Idempotent: `<preConditions onFail="MARK_RAN"><not><tableExists tableName="projects"/></not></preConditions>`
    - Register the file in `changelog.xml`
    - _Requirements: 1.4, 1.6, 1.7_

- [x] 2. Checkpoint — backend compiles
  - Run `./gradlew compileJava compileTestJava` (redirect to temp log); ensure the entity, enum, and changeset compile. Ask the user if questions arise.
  - _Requirements: 1.1, 1.3_

- [x] 3. DAO, request/response models, custom-create orchestrator
  - [x] 3.1 Create `ProjectDao extends AdminDao<ProjectEntity, Long>`
    - Standard JPA/Specification DAO
    - _Requirements: 3.2_
  - [x] 3.2 Define request/response/DTO models
    - `CreateProjectRequest` (base fields with bean-validation: `name` `@NotBlank` 1..255, `area` 0.01..999999999.99 when present, `status` optional; `members: List<ProjectMemberInput{userId @NotNull, projectRoleId @NotNull}>`; optional `ClientBlock{ existingClientUserId | newClient{name @NotNull, email @NotNull, phone?, locale?} }`, no client role identifier)
    - `ProjectUpdateRequest` (base fields only)
    - `ProjectListDto`/`ProjectReadDto` exposing base fields + `members: List<ProjectMemberSummaryDto>` + derived `client: ProjectMemberSummaryDto?`
    - `ProjectMemberSummaryDto{userId, userName, roleCode, roleName}`
    - _Requirements: 2.2, 2.4, 2.5, 3.3, 3.4_
  - [x] 3.3 Create `ProjectService implements ProjectScopedService<...>` with CRUD plumbing and anchor override
    - Supply `getDao`/`getMapper`/`getEntityManager`/`getDaoModelClass`/`getAuditLogDao`, `allowedProjectIds(userId) → projectAccessCache.get(userId)`, and `getProjectIdPath() → "id"`
    - Override generic `create(...)` (single and bulk signatures) to throw `ForemenApiException(BAD_REQUEST, "error.project.create.unsupported")`
    - _Requirements: 3.1, 3.2, 4.1_
  - [x] 3.4 Implement date-range + base-field validation guard
    - `validateDateRange` rejecting `endDate` earlier than `startDate` with `error.project.date.range`; ensure `name`/`area`/`status` validation reject invalid values with a client error identifying the field, persisting nothing
    - _Requirements: 3.5, 3.6, 8.8_
  - [x] 3.5 Implement `createProject(CreateProjectRequest)` transactional orchestrator
    - `@Transactional`: validate base fields + date range → resolve `googlePlaceId` details when needed → build/persist `ProjectEntity` defaulting `status` to `DRAFT` when null → assign each `members[i]` via `ProjectMemberService.assign(userId, projectId, projectRoleId)` → process `client` (existing → `assign(existingId, projectId, clientRole.id)`; newClient → reuse FOR-03-05 `ClientRegistrationService.register(...)` with server-resolved CLIENT role) → return persisted project
    - Any thrown exception rolls back the whole unit; surface `ProjectMemberService`/client errors per the Error Handling table
    - _Requirements: 2.1, 2.3, 2.6, 2.7, 2.8, 2.9, 3.7, 3.8, 3.9_
  - [x] 3.6 Write property test P1 for status default
    - **Property 1: Status defaults to DRAFT**
    - Tag comment `Feature: FOR-04-13-project, Property 1`; jqwik, min 100 iterations; class `ProjectCreateStatusDefaultPropertyTest`
    - **Validates: Requirements 2.3**
  - [x] 3.7 Write property test P2 for create atomicity
    - **Property 2: Project creation is all-or-nothing** (failure-injection over generated failing steps)
    - jqwik, min 100 iterations; class `ProjectCreateAtomicityPropertyTest`
    - **Validates: Requirements 2.6, 2.9**
  - [x] 3.8 Write property test P3 for server-resolved CLIENT role
    - **Property 3: The client is always assigned under the server-resolved CLIENT role**
    - jqwik, min 100 iterations; class `ProjectClientRolePropertyTest`
    - **Validates: Requirements 2.7, 2.8**
  - [x] 3.9 Write property test P4 for base-field validation
    - **Property 4: Invalid base fields are rejected and nothing persists**
    - jqwik, min 100 iterations; class `ProjectFieldValidationPropertyTest`
    - **Validates: Requirements 3.5**
  - [x] 3.10 Write property test P5 for date range
    - **Property 5: End date must not precede start date**
    - jqwik, min 100 iterations; class `ProjectDateRangePropertyTest`
    - **Validates: Requirements 3.6, 8.8**

- [x] 4. Google Places proxy (relocated) + config
  - [x] 4.1 Add `GooglePlacesProperties` with fail-fast config
    - `@Validated @ConfigurationProperties("foremen.google-places")` record `{ boolean enabled, String apiKey }`; `@PostConstruct`/`InitializingBean` throws on startup when `enabled` and `apiKey` blank
    - Document `foremen.google-places.api-key` / `GOOGLE_PLACES_API_KEY` in `application.yml` and `.env.example`
    - _Requirements: 5.4, 5.5_
  - [x] 4.2 Add `GooglePlacesClient` interface + `RestClientGooglePlacesClient` and `GooglePlacesService`
    - Client wraps the two Google HTTP calls injecting the server-side key (mockable); service normalizes into `PlacePredictionDto{description, placeId}` / `PlaceDetailsDto{formattedAddress, latitude, longitude, components}` and exposes `resolveDetails(placeId)` used by the controller and by `ProjectService.createProject`/`update`; never echoes the key
    - _Requirements: 5.1, 5.2, 5.3, 5.7_
  - [x] 4.3 Write `GooglePlacesServiceTest`
    - Normalize a mocked client response into the DTO shapes; assert the key never appears in outputs
    - _Requirements: 5.1, 5.2, 5.3_
  - [x] 4.4 Create standalone `AddressController` at `/api/addresses/*`
    - `@GetMapping("/autocomplete")` and `@GetMapping("/details")` wrapping `GooglePlacesService`; carries NONE of `@PermissionResource`/`@PermissionOperation`/`@RequiresPermission` (authenticated-any-user, intentionally unguarded by ABAC); ensure `/api/addresses/**` requires an authenticated principal in the security chain
    - _Requirements: 5.1, 5.2, 5.3, 5.6_

- [x] 5. `ProjectController` wiring
  - [x] 5.1 Create `ProjectController implements AdminController<...>` at `/api/projects`
    - `@PermissionResource("PROJECTS")`; inherit generic list/read/update/delete/count/metadata/i18n (each keeping its `@PermissionOperation`); add custom `@PostMapping createProject` with `@RequiresPermission(resource="PROJECTS", operation="CREATE")` returning HTTP 201; supply `getMapper()`/`getService()`
    - _Requirements: 2.1, 2.10, 3.2, 6.1, 6.2_

- [x] 6. Checkpoint — backend compiles + startup annotations
  - Run `./gradlew compileJava compileTestJava` (temp log). Confirm `ProjectController` is fully annotated and `AddressController` is intentionally unguarded so `PermissionAnnotationValidator` is satisfied. Ask the user if questions arise.
  - _Requirements: 6.2_

- [x] 7. Nested member/client filtering + metadata + list projection
  - [x] 7.1 Wire list/read projection mapping for members and derived client
    - Map `members` collection into `ProjectMemberSummaryDto` and derive `client` as the single member whose `roleCode == "CLIENT"` (null when none); batch-hydrate via the `@BatchSize` collection to avoid N+1
    - _Requirements: 3.4_
  - [x] 7.2 Extend `EntityMetadataResolver` to advertise the `members` collection's reference leaves
    - Emit reference descriptors for `members.user.id` (target `users`, options `/api/users`, user display-name label) and `members.projectRole.code` (target `roles`), collection-qualified `idPath`; reuse existing `SpecificationBuilder` nested-collection joins (`members.user.id~in~...`, `members.projectRole.code==CLIENT`) with `distinct`
    - _Requirements: 3.4, 8.2_
  - [x] 7.3 Write property test P6 for membership list filter
    - **Property 6: Non-ADMIN list returns exactly the caller's membership set** (generated membership graphs)
    - jqwik, min 100 iterations; class `ProjectMembershipListFilterPropertyTest`
    - **Validates: Requirements 4.2, 4.4**
  - [x] 7.4 Write property test P7 for member filter
    - **Property 7: Member filter returns exactly the projects having a matching member** (generated graphs, deduplicated)
    - jqwik, min 100 iterations; class `ProjectMemberFilterPropertyTest`
    - **Validates: Requirements 8.2**
  - [x] 7.5 Write property test P8 for client filter
    - **Property 8: Client filter returns exactly the projects whose CLIENT member matches** (compound predicate over generated graphs incl. CLIENT and non-CLIENT roles)
    - jqwik, min 100 iterations; class `ProjectClientFilterPropertyTest`
    - **Validates: Requirements 8.1, 8.2**

- [x] 8. ABAC seed
  - [x] 8.1 Create Liquibase `041-seed-projects-resource.xml` and register it last in `changelog.xml`
    - Resource row `code='PROJECTS'` with non-null `name_ru`/`name_pl`/`description_ru`/`description_pl`; grants ADMIN=CREATE,READ,UPDATE,DELETE; MANAGER=CREATE,READ,UPDATE; FOREMAN/WORKER/FINANCIER/CLIENT=READ; DELETE ADMIN-only
    - Idempotent per role/resource via `<preConditions onFail="MARK_RAN"><sqlCheck expectedResult="0">...`; register after `039-seed-work-prices-resource.xml` at sequence `040+`
    - _Requirements: 6.3, 6.4, 6.5, 6.6, 6.7_

- [x] 9. Backend integration + smoke tests (Testcontainers)
  - [x] 9.1 Write `ProjectCustomCreateIT`
    - 7.1 existing-client create, 7.2 newClient → INVITED user + invite dispatched + CLIENT assignment, 7.3 failure rolls back (no project/member/client), 7.4 generic create rejected and nothing persisted
    - _Requirements: 7.1, 7.2, 7.3, 7.4_
  - [x] 9.2 Write `ProjectScopingIT`
    - 7.5 non-ADMIN LIST membership-filtered; ADMIN bypass (Req 4.3); non-member by-id read/update/delete denial returning 404 (Req 4.5)
    - _Requirements: 7.5, 4.3, 4.5_
  - [x] 9.3 Write `ProjectCrudIT`
    - 7.6 read/update/delete lifecycle, 7.7 filter on `status`
    - _Requirements: 7.6, 7.7_
  - [x] 9.4 Write `ProjectMemberFilterIT`
    - Nested member filter `members.user.id~in~<ids>` returns exactly matching projects deduplicated; compound client filter `members.user.id~in~<ids> AND members.projectRole.code==CLIENT` returns exactly projects whose CLIENT member matches, excluding non-CLIENT-only matches; assert list/read DTO exposes `members` + derived `client`
    - _Requirements: 8.1, 8.2, 3.4_
  - [x] 9.5 Write `AddressProxyIT`
    - `/api/addresses/autocomplete` (7.8) + `/api/addresses/details` (7.9) with mocked `GooglePlacesClient`, key absent from responses; authenticated non-`PROJECTS` caller reaches endpoints, anonymous caller gets 401
    - _Requirements: 7.8, 7.9, 5.3, 5.6_
  - [x] 9.6 Write `ProjectsSeedIT`
    - 7.11 exactly one `PROJECTS` resource, 7.12 exact grant matrix (DELETE=ADMIN only), 7.13 idempotent re-apply leaves counts unchanged; plus create-table migration presence/shape (Req 1.4–1.7)
    - _Requirements: 7.11, 7.12, 7.13, 1.4, 1.6, 1.7_
  - [x] 9.7 Write `GooglePlacesConfigStartupTest`
    - Context fails to start when feature enabled and key blank
    - _Requirements: 5.5, 7.10_
  - [x] 9.8 Write clean-startup annotation-coverage smoke test
    - Confirm fully annotated `ProjectController` and intentionally ABAC-unannotated `AddressController` both start cleanly under `PermissionAnnotationValidator`
    - _Requirements: 6.2_

- [x] 10. Checkpoint — run only the affected backend test classes
  - Run the property/unit/IT/smoke classes added above with `--tests` filters (redirect to temp log; read JUnit XML). Do NOT run the full suite. Ask the user if questions arise.
  - _Requirements: 7.1, 7.11_

- [x] 11. Frontend feature scaffolding (`features/projects/`)
  - [x] 11.1 Create types, zod schemas, and API modules
    - DTO/types; zod schema incl. `endDate >= startDate` refinement; `api/projects-api.ts` (`fetchProjects` via `buildFetchQuery`, `fetchProject`, `createProject` custom endpoint, `updateProject`, `deleteProject`); standalone reusable `api/address-api.ts` (`autocompleteAddress`, `fetchAddressDetails` → `/api/addresses/*`)
    - _Requirements: 8.4, 8.8, 8.11_
  - [x] 11.2 Create `ProjectStatusBadge` and cell components
    - `ProjectStatusBadge` (one localized badge per status), `ProjectMembersCell` (one `"{userName} — {roleName}"` line per member, placeholder when empty), `ProjectClientCell` (CLIENT member name, placeholder when none)
    - _Requirements: 8.1, 8.2_
  - [x] 11.3 Create filter controls
    - `ProjectMembersFilter` (users multi-select emitting `members.user.id~in~<ids>`, single → `members.user.id==<id>`); `ProjectClientFilter` (independent multi-select emitting `members.user.id~in~<...> AND members.projectRole.code==CLIENT`)
    - _Requirements: 8.2_
  - [x] 11.4 Create `GoogleAddressAutocomplete` and `ClientBlock`
    - Debounced `/api/addresses/autocomplete`; on selection `/api/addresses/details` storing `googlePlaceId`/`formattedAddress`/`latitude`/`longitude`; `ClientBlock` toggles existing-CLIENT selection vs reused invite form (name/phone/email, no role selector)
    - _Requirements: 8.4_
  - [x] 11.5 Create `ProjectFormSheet` and `DeleteProjectDialog`
    - Create form: base fields (`name`, `area`, `startDate`, `endDate`, `status` select) + `GoogleAddressAutocomplete` + team multi-select + `ClientBlock`; edit form uses generic update (base fields only); block submit + retain values + localized validation on invalid field and `endDate < startDate`; `DeleteProjectDialog` confirm + delete
    - _Requirements: 8.4, 8.5, 8.6, 8.7, 8.8, 8.9_
  - [x] 11.6 Create `ProjectsList` and `ProjectsPage`
    - `DataTable<ProjectDto>` `resource="PROJECTS"`, columns `name`, `address`/`formattedAddress`, `area`, `startDate`, `endDate`, `status` badge, `members` custom cell, `client` custom cell; server-side search/sort/filter/pagination; empty-state; gate create/edit/delete via `usePermission('PROJECTS', ...)`; wire members + client filters; `ProjectsPage` composes list/form/delete; invalidate query + success/error toasts on mutations
    - _Requirements: 8.1, 8.2, 8.3, 8.10, 8.11, 8.12_
  - [x] 11.7 Register `/projects` route + interim guard + i18n
    - Register `path: 'projects'` in `app/router.tsx`; add `'/projects': { resource: 'PROJECTS', operation: 'READ' }` to `route-permissions.ts`; add `projects.*` keys (incl. five status labels, Google address component text, Client block text) at parity in `pl.json` and `ru.json`, no raw key rendered
    - _Requirements: 8.13, 8.14_

- [x] 12. Frontend tests (Vitest + RTL, run with `--run`)
  - [x] 12.1 Write `ProjectFormSheet.test.tsx`
    - Google autocomplete calls mocked `/api/addresses/*` and stores `googlePlaceId`/`formattedAddress`/`latitude`/`longitude`; team member selectable; Client block supports existing-client and new-client cases
    - _Requirements: 9.1_
  - [x] 12.2 Write `ProjectsList.test.tsx`
    - Non-empty renders `name`/`address`/`area`/`startDate`/`endDate`/`status` with row count == record count; each of five statuses renders its localized badge; gated controls appear/hide with permission; empty result shows empty state with zero data rows
    - _Requirements: 9.2, 9.3, 9.4, 9.5_
  - [x] 12.3 Write `ProjectMembersCell.test.tsx` and `ProjectClientCell.test.tsx`
    - Members cell renders one `"{userName} — {roleName}"` per member (placeholder when empty); client cell renders only the CLIENT member's name (placeholder when none)
    - _Requirements: 9.2_
  - [x] 12.4 Write `ProjectMembersFilter.test.tsx` and `ProjectClientFilter.test.tsx`
    - Members filter emits `members.user.id~in~<ids>` (single → `==`) and composes into fetch query; client filter emits compound `members.user.id~in~<...> AND members.projectRole.code==CLIENT`, independent of members filter, both active simultaneously
    - _Requirements: 9.4_
  - [x] 12.5 Write `projects.i18n.test.ts`
    - `projects.*` key sets identical in `pl.json` and `ru.json` (incl. five status labels, Google address component text, Client block text), all non-empty, no raw key rendered
    - _Requirements: 8.13, 9.6_

- [x] 13. Checkpoint — frontend tsc + new UI test files
  - Run frontend type-check and the new Vitest files with `--run`. Ask the user if questions arise.
  - _Requirements: 9.1, 9.2_

- [x] 14. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression group, MD report template). This is an API+UI spec: include API tests against the Dockerized app for backend endpoints (`POST /api/projects` custom create, generic read/update/delete, `status`/member/client filters, `/api/addresses/*` proxy, ABAC seed/grants) and browser-engine scenarios for the admin `/projects` UI (list, create form with Google autocomplete + team + client block, edit, delete, permission gating, i18n). Result artifacts are MD reports with tables. Write test-cases.md in Russian per the standard.
  - _Requirements: 1, 2, 3, 4, 5, 6, 8_

- [x] 15. Final checkpoint — affected backend classes + frontend tsc/UI tests
  - Run the affected backend test classes with `--tests` (temp log + JUnit XML) and the frontend `--run` tests; confirm all pass. Do NOT run the full suite unless explicitly requested. Ask the user if questions arise.
  - _Requirements: 7.1, 7.11, 9.1, 9.2_

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP.
- Each task references specific requirements for traceability.
- Property tests P1–P8 use jqwik with a minimum of 100 iterations and a `Feature: FOR-04-13-project, Property {n}` tag comment, one test class per property.
- The design has a Correctness Properties section, so property test sub-tasks are included alongside unit/integration/smoke tests.
- Per the workspace test-execution standard, run only the affected test classes with `--tests`, redirect to a temp log, and read the JUnit XML for pass/fail; the full `./gradlew build` runs only on explicit request (~20 min).
- The Google Places proxy lives on a standalone, ABAC-unannotated `AddressController` at `/api/addresses/*` (authenticated-any-user), reusable by future features; this supersedes the original `/api/projects/address/*` + `PROJECTS`/`READ` wording (design Change 1).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.3", "1.4", "4.1"] },
    { "id": 1, "tasks": ["1.2", "3.1", "3.2", "4.2"] },
    { "id": 2, "tasks": ["3.3", "4.3", "4.4"] },
    { "id": 3, "tasks": ["3.4", "5.1"] },
    { "id": 4, "tasks": ["3.5", "7.1", "7.2", "8.1"] },
    { "id": 5, "tasks": ["3.6", "3.7", "3.8", "3.9", "3.10", "7.3", "7.4", "7.5"] },
    { "id": 6, "tasks": ["9.1", "9.2", "9.3", "9.4", "9.5", "9.6", "9.7", "9.8"] },
    { "id": 7, "tasks": ["11.1", "11.2", "11.3", "11.4"] },
    { "id": 8, "tasks": ["11.5", "11.6", "11.7"] },
    { "id": 9, "tasks": ["12.1", "12.2", "12.3", "12.4", "12.5"] }
  ]
}
```
