# Implementation Plan — FOR-04-01: Table Reference-Entity Filtering

- [ ] 1. Enrich table metadata with a reference descriptor
  - [ ] 1.1 Add `ReferenceInfo` to `MetadataResponse.FieldInfo`
    - Add optional `ReferenceInfo(targetResource, optionsPath, labelField, labelI18n, idPath)` record to `com.foremen.controller.model.MetadataResponse` and an additional `reference` component on `FieldInfo` (nullable); keep `name`/`dataType`/`i18n`/`nested` unchanged
    - _Requirements: 1.1, 1.5_

  - [ ] 1.2 Build a `ReferenceResourceRegistry` (entity type → resource code + base path)
    - At startup, scan `RequestMappingHandlerMapping` for `AdminController`/`AdminReadOnlyController` beans, read each controller's `@PermissionResource` value and `@RequestMapping` base path, and resolve the managed entity generic type; expose `lookup(Class<?> entityType) → (resourceCode, basePath)`; provide a declarative fallback map for unresolved types
    - _Requirements: 1.1_

  - [ ] 1.3 Emit `ReferenceInfo` for `@ManyToOne`/`@OneToOne` fields in `EntityMetadataResolver`
    - In the nested-entity branch, set `idPath = fieldName + ".id"`, resolve `targetResource`/`optionsPath` via the registry, resolve `labelField`/`labelI18n` from the target entity's metadata (`name` + `nameRU`/`namePL` → i18n true; else first STRING field; else `id`); leave `reference` null for scalar fields; preserve the existing `IN_PROGRESS` recursion guard and per-class cache
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

  - [ ] 1.4 Write metadata unit tests
    - JUnit 5: `@ManyToOne` field (e.g. `UserEntity.role`) yields a `ReferenceInfo` with `idPath=role.id`, `targetResource=ROLES`/`optionsPath=/api/roles`, `labelField=name`, `labelI18n=true`; a scalar field yields `reference=null`; assert existing `FieldInfo` shape unchanged (backward compat)
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [ ] 2. Checkpoint - Ensure all tests pass
  - Run only the affected test classes; read results from a temp log / JUnit XML.

- [ ] 3. Confirm options listing over the existing list endpoint
  - [ ] 3.1 Verify name-ordered, searchable, paginated options via the target list endpoint
    - Confirm `GET /api/{target}?sort=name,asc&query=name=ct=<term>&page&size` returns locale-name-ordered, case-insensitive-name-filtered, paginated results using the existing `ReadOnlyAdminService` i18n sort/filter resolution; add a lightweight options projection ONLY if the full DTO proves too heavy (document the decision)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.6_

  - [ ] 3.2 Write options-endpoint integration tests
    - `@SpringBootTest` + MockMvc + Testcontainers: options are name-ascending (locale-resolved), `name=ct=` filters case-insensitively, pagination reports more pages, and the endpoint is guarded by target `READ` (401 no token / 403 no grant / 200 with grant or ADMIN)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

  - [ ] 3.3 Write end-to-end reference-filter query integration tests
    - `@SpringBootTest` + MockMvc: `GET /api/users?query=role.id==<id>` returns only users with that role; `GET /api/users?query=role.id=in=(<id1>,<id2>)` returns users with either role; composition with a second filter via AND works
    - _Requirements: 3.4, 4.2, 5.2_

- [ ] 4. Checkpoint - Ensure all tests pass
  - Run only the affected test classes; read results from a temp log / JUnit XML.

- [ ] 5. Frontend types and filter-emission
  - [ ] 5.1 Add `ReferenceInfo` type and extend `ColumnConfig`
    - Mirror the backend `ReferenceInfo` in the frontend metadata types; populate an optional `reference` on the table column config from `GET /{resource}/metadata`
    - _Requirements: 1.1, 5.1_

  - [ ] 5.2 Implement the pure filter-fragment emitter
    - Pure function `(idPath, ids) → fragment`: `[]` → none; `[id]` → `${idPath}==${id}`; `[...>1]` → `${idPath}=in=(${ids.join(',')})`; used by single and multi modes
    - _Requirements: 3.4, 4.2, 4.3_

  - [ ] 5.3 Write a property test for the filter-fragment emitter
    - fast-check PBT: for any non-empty positive-id set and non-empty `idPath`, one id → `==`, many → comma-joined `=in=(...)` (order-stable), empty → no fragment
    - _Requirements: 3.4, 4.2, 4.3_

- [ ] 6. Frontend `ReferenceFilter` component
  - [ ] 6.1 Implement the infinite-scroll, backend-searched dropdown
    - `useInfiniteQuery` keyed by `[targetResource, search]` calling `optionsPath?page&size&sort=name,asc&query=name=ct=<search>` through the shared `apiRequest`; next page on scroll-end; debounced search resets to page 0; render options by locale-resolved name; loading and empty-state per i18n
    - _Requirements: 3.2, 3.3, 6.1, 6.3, 6.4_

  - [ ] 6.2 Implement single-select and multi-select modes with a mode toggle
    - Single: pick replaces + closes, emits `[id]`; multi: checkboxes toggle membership; header toggle switches modes preserving compatible selection; clear empties selection; selected value shows locale name
    - _Requirements: 3.1, 3.4, 3.5, 3.6, 4.1, 4.4, 4.5, 4.6_

  - [ ] 6.3 Handle permission degradation and errors
    - On options 403 → disabled control with localized "no access" hint (table still loads); on network/5xx → localized error+retry inside the dropdown
    - _Requirements: 5.5, 6.2_

  - [ ] 6.4 Add i18n keys (PL + RU)
    - Add reference-filter UI keys (search placeholder, loading, empty-state, select/clear, single/multi toggle, no-access) to `pl.json` and `ru.json`
    - _Requirements: 6.2_

  - [ ] 6.5 Write `ReferenceFilter` component tests
    - Infinite scroll loads next page; debounced search hits options with `name=ct=`; single emits `[id]`; multi toggles; mode toggle preserves selection; 403 → disabled; empty-state renders
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 4.4, 4.5, 5.5, 6.4_

- [ ] 7. DataTable integration (default for all tables)
  - [ ] 7.1 Render `ReferenceFilter` for reference columns and compose filters
    - In the shared DataTable filter layer, choose `ReferenceFilter` when `column.reference` is present (else existing filters unchanged); own per-column selected-ids state; compose the emitted reference fragment(s) with other active filters via the existing AND joiner into the single `query` param; participate in the table's filter/URL state
    - _Requirements: 5.1, 5.2, 5.3, 3.4, 4.2_

  - [ ] 7.2 Wire Users-by-Role as the reference implementation
    - Ensure the Users table renders the Role column with a working `ReferenceFilter` (single and multi) end to end; confirm no behavioral change on tables without reference fields
    - _Requirements: 5.4, 5.3_

  - [ ] 7.3 Write DataTable integration tests
    - A reference column renders `ReferenceFilter`; a reference filter + a text filter compose with AND; a table without reference fields is unchanged
    - _Requirements: 5.1, 5.2, 5.3_

- [ ] 8. Checkpoint - Ensure all tests pass
  - Run only the affected frontend test files + backend classes; read results from a temp log.

- [ ] 9. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression group, MD report template). This spec has both an API surface and browser screens: include API tests against the Dockerized app (metadata reference descriptor, options endpoint ordering/search/pagination/permission, `role.id==` and `role.id=in=(...)` filtering) and browser-engine UI scenarios for the reference dropdown (infinite scroll, search, single/multi select, clear, mode toggle). Result artifacts are MD reports with tables.
  - _Requirements: 1, 2, 3, 4, 5, 6_

- [ ] 10. Final checkpoint - Ensure all tests pass
  - Ensure the backend build compiles and the affected frontend + backend tests pass (per the test-execution rules — targeted runs, not the full suite).
  - _Requirements: 1.1, 2.1, 3.4, 4.2, 5.1, 5.2_

## Notes

- Backend query grammar (`==`, `=in=`, dotted paths, AND) already exists (FOR-01/02) — no changes to `QueryParser`/`QueryTokenizer`.
- Options listing reuses the target resource's existing guarded list endpoint (FOR-03-08) — no parallel endpoint unless justified in design.
- This is the default table behavior; all FOR-04 dictionary/entity tables inherit it via metadata with no per-table work.
