# Implementation Plan: Audit Log UI (FOR-02-06a-audit)

## Overview

Implement a full-page audit log viewer at `/audit` and an "Audit" action button in the generic DataTable template that opens a filtered modal. Backend: read-only audit controller at `/api/audit` using `AdminReadOnlyController`, new ABAC resource `AUDIT` with READ for ADMIN (Liquibase). Frontend: feature module at `src/features/audit/`, `AuditModal` in shared DataTable, `showAuditButton` prop on DataTable, `JsonExpander` component. Tech stack: Java 21 / Spring Boot 3 (backend), TypeScript 5 / React 19 / TanStack Query 5 / shadcn/ui (frontend).

## Tasks

- [x] 1. Backend: Liquibase seed for AUDIT resource and ABAC permissions
  - [x] 1.1 Create 009-seed-audit-resource.xml changeset
    - Create `foremen-backend/database_files/changesets/009-seed-audit-resource.xml`
    - First changeSet `009-seed-audit-resource`: insert into `resources` table — code=AUDIT, name_ru=Аудит, name_pl=Audyt, description_ru=Журнал аудита, description_pl=Dziennik audytu. Use precondition `sqlCheck` to prevent duplicate (SELECT COUNT WHERE code='AUDIT' = 0)
    - Second changeSet `009-seed-audit-permissions`: insert into `role_resources` for ADMIN+AUDIT, then insert into `role_resource_operations` for READ only. Use precondition to prevent duplicate
    - Register the new file in `foremen-backend/database_files/changelog.xml` after existing entries
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [x] 2. Backend: Audit read-only service stack (DAO, mapper, models, service, controller)
  - [x] 2.1 Create AuditReadOnlyDao, service models, and service mapper
    - Create `com.foremen.dao.AuditReadOnlyDao` — interface extending `ReadOnlyAdminDao<AuditLogEntity, Long>`, annotated with `@Repository`
    - Create `com.foremen.service.model.AuditServiceModel` — Java record with fields: id (Long), entityClass (String), entityId (Long), operation (String), performedBy (String), performedAt (LocalDateTime), snapshotBefore (Map<String, Object>), snapshotAfter (Map<String, Object>)
    - Create `com.foremen.service.model.AuditServiceExtendedModel` — identical fields to AuditServiceModel (no i18n)
    - Create `com.foremen.service.model.mapper.AuditServiceMapper` — `@Mapper(config = ForemenMapperConfig.class)` extending `ServiceToDaoMapper<AuditLogEntity, AuditServiceModel, AuditServiceExtendedModel>`. Override `getI18nSupportedProperties()` returning `Set.of()`. Add `@Named("jsonStringToMap")` default method converting JSON String → Map<String, Object> via Jackson (null/blank → null, invalid JSON → Map.of("_raw", json)). Apply `@Mapping(qualifiedByName = "jsonStringToMap")` on snapshotBefore/snapshotAfter in `toServiceModel` and `toExtendedServiceModel`
    - _Requirements: 2.1, 2.4, 2.5_

  - [x] 2.2 Create AuditService and AuditController
    - Create `com.foremen.service.AuditService` — `@Service @RequiredArgsConstructor @Getter` implementing `ReadOnlyAdminService<AuditServiceModel, AuditServiceExtendedModel, AuditLogEntity, Long>`. Fields: `readDao` (AuditReadOnlyDao), `mapper` (AuditServiceMapper), `entityManager` (EntityManager), `daoModelClass` = AuditLogEntity.class
    - Create `com.foremen.controller.AuditController` — `@RestController @RequestMapping("/api/audit") @RequiredArgsConstructor` implementing `AdminReadOnlyController<AuditServiceModel, AuditServiceExtendedModel, AuditLogEntity, Long>`. Single field: `auditService`. Override `getService()` returning `auditService`
    - The `/metadata` endpoint is provided automatically by `AdminReadOnlyController` default method (with Cache-Control: max-age=86400)
    - _Requirements: 2.1, 2.2, 2.3, 2.6_

  - [x] 2.3 Write unit tests for AuditServiceMapper
    - Test `jsonStringToMap`: valid JSON → correct Map
    - Test `jsonStringToMap`: null → null
    - Test `jsonStringToMap`: blank string → null
    - Test `jsonStringToMap`: invalid JSON → Map with `_raw` key
    - Test `getI18nSupportedProperties()` returns empty Set
    - _Requirements: 2.4, 2.5_

  - [x] 2.4 Write integration tests for AuditController
    - Seed test audit_log entries via TestContainers
    - Test GET `/api/audit` → 200 with paginated response containing seeded records
    - Test GET `/api/audit?query=entityClass==RoleEntity` → filtered results
    - Test GET `/api/audit?sort=performedAt,desc` → sorted results
    - Test GET `/api/audit/metadata` → 200 with field list + Cache-Control header
    - Test POST `/api/audit` → 405 Method Not Allowed
    - Test DELETE `/api/audit/1` → 405 Method Not Allowed
    - _Requirements: 2.1, 2.2, 2.3, 2.6_

- [x] 3. Checkpoint - Backend compiles and audit endpoint responds
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Frontend: Feature module setup (types, API, column config)
  - [x] 4.1 Create audit feature module structure and API layer
    - Create `src/features/audit/` directory with sub-folders: `components/`, `config/`, `api/`
    - Create `src/features/audit/api/audit-api.ts` — implement `fetchAuditRecords(params: FetchParams): Promise<PaginatedResponse<AuditRecord>>` using GET `/api/audit` with page, size, sort, query params. Follow existing pattern from roles-api.ts (URLSearchParams, error handling)
    - Create `src/features/audit/index.ts` barrel export
    - _Requirements: 3.1_

  - [x] 4.2 Create audit column configurations
    - Create `src/features/audit/config/audit-columns.ts` — export `auditFullColumns: ColumnConfig<AuditRecord>[]` with all 8 columns: id (number, sortable, filterable), entityClass (string, sortable, filterable), entityId (number, sortable, filterable), operation (string, sortable, filterable), performedBy (string, sortable, filterable), performedAt (date, sortable, filterable), snapshotBefore (not sortable, not filterable, not searchable, custom render with JsonExpander), snapshotAfter (same)
    - Export `auditModalColumns` — same as full columns but filtered to exclude entityClass and entityId
    - _Requirements: 3.2, 6.4_

  - [x] 4.3 Create JsonExpander component
    - Create `src/features/audit/components/JsonExpander.tsx`
    - When data is null/undefined: render `<span>—</span>` with muted-foreground color
    - When data is non-null: render "Show" button (ghost variant, size sm)
    - On click "Show": expand to show `<pre>` with `JSON.stringify(data, null, 2)`, styled with bg-muted, border-border, rounded-md, p-2, monospace font, overflow-x-auto, max-w-[300px]
    - On click "Hide": collapse back to button
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 9.2_

- [x] 5. Frontend: AuditPage and AuditModal
  - [x] 5.1 Create AuditPage component
    - Create `src/features/audit/AuditPage.tsx` — renders DataTable with `entityKey="audit"`, `columns={auditFullColumns}`, `fetchFn={fetchAuditRecords}`, `showAuditButton={false}`
    - Pass `defaultSort={[{ field: 'performedAt', direction: 'desc', priority: 1 }]}` to DataTable for default sort by performedAt descending (most recent first)
    - The operation column render function should translate operation codes using `t('audit.operation.CREATE')` etc.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

  - [x] 5.2 Create AuditModal component in shared DataTable directory
    - Create `src/components/data-table/AuditModal.tsx`
    - Props: `open`, `onClose`, `entityKey` (string — API path segment e.g. 'roles'), `entityId` (number)
    - Use TanStack Query to fetch from `GET /api/${entityKey}/audit/${entityId}` — reuse the existing `AdminController.getAudit()` default method which returns `List<AuditLogEntity>` ordered by performedAt ASC
    - Sort the received list client-side by performedAt DESC for display (most recent first)
    - Render a shadcn Table (Table, TableHeader, TableBody, TableRow, TableCell) directly — NOT a full DataTable, since the data is a simple non-paginated list
    - Use `auditModalColumns` config from `@/features/audit/config/audit-columns` (omitting entityClass and entityId)
    - Use shadcn/ui Dialog (DialogContent with `className="max-w-[90vw] max-h-[80vh] overflow-hidden flex flex-col"`)
    - DialogTitle: `{t('audit.modal.title')} — {entityKey} #{entityId}`
    - Show skeleton loading (5 rows) while query is loading
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 9.3, 10.2_

  - [x] 5.3 Add `showAuditButton` prop and Audit button to DataTable
    - Modify `src/components/data-table/types.ts` — add `showAuditButton?: boolean` to `DataTableProps<T>`
    - Modify `src/components/data-table/DataTable.tsx` (or DataTableBody/DataTableCards) — when `showAuditButton !== false`, render an Audit button (Lucide `ScrollText` icon, ghost variant, tooltip from `audit.button.viewAudit`) in row actions alongside existing `rowActions`
    - On Audit button click: open AuditModal with `entityKey` (the DataTable's entityKey prop, e.g. 'roles') and `entityId={row.id}`
    - Add `defaultSort?: SortState[]` to DataTableProps — when provided and no persisted state exists, use it as the initial sort state in `useTableState`
    - Manage modal open/close state within DataTable (local state for selected row)
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 9.4_

- [x] 6. Frontend: Navigation and i18n
  - [x] 6.1 Add audit translations to locale files
    - Add to `src/locales/ru.json`: nav.audit, audit.pageTitle, audit.column.* (id, entityClass, entityId, operation, performedBy, performedAt, snapshotBefore, snapshotAfter), audit.modal.title, audit.button.viewAudit, audit.operation.* (CREATE, UPDATE, DELETE, UPDATE_PERMISSIONS), audit.snapshot.show, audit.snapshot.hide — all with Russian values as specified in requirements
    - Add to `src/locales/pl.json`: same keys with Polish values
    - _Requirements: 8.1, 8.2, 8.3, 8.4_

  - [x] 6.2 Add audit route and navigation entry
    - Add lazy-loaded route for `/audit` → AuditPage in the router config (React.lazy + Suspense with Skeleton fallback)
    - Add Nav_Item to navigation config in "System" section: `{ path: '/audit', labelKey: 'nav.audit', icon: 'ScrollText', bottomNav: false }`
    - Ensure Top_Bar displays `audit.pageTitle` when on `/audit` route
    - _Requirements: 7.1, 7.2, 7.3_

- [x] 7. Checkpoint - Full page renders, modal opens from DataTable, navigation works
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Write frontend tests
  - [x] 8.1 Write component tests for JsonExpander
    - Test renders dash when data is null
    - Test renders "Show" button when data is non-null
    - Test click "Show" expands to formatted JSON in `<pre>`
    - Test click "Hide" collapses back
    - Test null/undefined renders dash with muted styling
    - _Requirements: 4.1, 4.2, 4.3, 4.4_

  - [x] 8.2 Write component tests for AuditModal
    - Test Dialog renders when open=true
    - Test Dialog not rendered when open=false
    - Test title includes entityClass and entityId
    - Test contains DataTable with showAuditButton=false
    - Test onClose called when Dialog dismissed
    - _Requirements: 6.1, 6.2, 6.3, 6.5_

  - [x] 8.3 Write component tests for DataTable audit button integration
    - Test audit button renders by default (showAuditButton undefined)
    - Test audit button renders when showAuditButton=true
    - Test audit button NOT rendered when showAuditButton=false
    - Test click on audit button opens AuditModal with correct entityClass and entityId
    - _Requirements: 5.1, 5.2, 5.3, 5.4_

  - [x] 8.4 Write tests for AuditPage and i18n
    - Test AuditPage renders DataTable with correct columns
    - Test DataTable has showAuditButton=false on audit page
    - Test all audit i18n keys exist in pl.json
    - Test all audit i18n keys exist in ru.json
    - _Requirements: 3.1, 3.5, 8.4_

- [x] 9. Final checkpoint - All tests pass, feature complete
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- The audit entity is immutable — no create/update/delete endpoints or UI actions
- The `AdminReadOnlyController` pattern provides `/metadata` endpoint automatically (no extra code)
- `AuditLogEntity` already exists at `com.foremen.service.audit.AuditLogEntity` — no entity creation needed
- The existing `AuditLogDao` (JpaRepository) is separate from the new `AuditReadOnlyDao` (ReadOnlyAdminDao) — both can coexist, one for internal audit writes, one for the admin read API
- The `showAuditButton` prop defaults to `true`, so all existing DataTable usages (Roles page, etc.) automatically get the Audit button
- The Audit page sets `showAuditButton={false}` to prevent recursive audit buttons
- Backend does NOT use i18n for audit fields — all data is system-generated strings
- The JSON snapshot mapper uses Jackson fallback for malformed JSON (returns `{_raw: original}`)
- Navigation uses Lucide `ScrollText` icon for the audit menu item
- Property-based testing is not applicable for this feature (see design document Testing Strategy)
- The Audit_Modal uses the existing `GET /api/{entity}/audit/{id}` endpoint (AdminController default method) which returns a simple list — no pagination needed in the modal. The modal renders a basic shadcn Table, not a full DataTable.
- The Audit page applies `performedAt,desc` as default sort so most recent events appear first

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "4.1"] },
    { "id": 1, "tasks": ["2.1", "4.2", "4.3"] },
    { "id": 2, "tasks": ["2.2", "5.1"] },
    { "id": 3, "tasks": ["2.3", "2.4", "5.2"] },
    { "id": 4, "tasks": ["5.3", "6.1"] },
    { "id": 5, "tasks": ["6.2"] },
    { "id": 6, "tasks": ["8.1", "8.2", "8.3", "8.4"] }
  ]
}
```
