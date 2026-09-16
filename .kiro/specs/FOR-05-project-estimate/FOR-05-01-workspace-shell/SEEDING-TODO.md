# FOR-05-01 — Follow-up: sibling-spec resource seeding (TODO)

## Why this file exists

FOR-05-01 (Project Workspace Shell) is a **frontend-only** spec. It declares the workspace tab
contract (`WORKSPACE_TABS`) in which **every** tab carries a mandatory `requiredPermission`
`{ resource, operation: 'READ' }` and is permission-gated exactly like a main-menu entry
(`isNavItemVisible`).

Several of those tab resources are **referenced but NOT seeded** by FOR-05-01:

- `PROJECT_PRICING`
- `WORK_SCHEDULE`
- `CONTRACTS`
- `DOCUMENTS`
- `WORK_REPORTS`
- `PAYROLL`
- `AMENDMENTS`
- `PRICE_HISTORY`

Because these resource rows do not exist in the backend yet, `usePermission` cannot grant a
`READ` on them, so `resolveWorkspaceTabs` simply **hides** each corresponding tab. This is the
intended graceful-degradation behavior (Requirement 11.5): the shell does not crash and the tab
stays hidden until its owning sibling spec seeds the resource.

Each tab becomes visible to permitted users **only after** its owning sibling spec seeds both the
`resources` row **and** the ADMIN (plus any other role) grants, following the
[`.kiro/steering/entity-creation-rules.md`](../../../steering/entity-creation-rules.md) checklist
(seed changeset registered last in `changelog.xml`, `role_resources` + `role_resource_operations`
grants, `@PermissionResource` on the controller, and `getProjectIdPath()` for project-scoped
entities).

## Owner mapping checklist

Check each item off once the owning spec has seeded the resource row + role grants and the tab
appears correctly for permitted users.

- [ ] `pricing` → **FOR-05** — resource `PROJECT_PRICING`
- [ ] `contract` → **FOR-05** — resource `CONTRACTS`
- [ ] `amendments` → **FOR-05** — resource `AMENDMENTS`
- [ ] `priceHistory` → **FOR-05** — resource `PRICE_HISTORY`
- [ ] `scheduleDesign` → **FOR-06** — resource `WORK_SCHEDULE`
- [ ] `planActual` → **FOR-06** — resource `WORK_REPORTS`
- [ ] `documents` → **FOR-10** — resource `DOCUMENTS`
- [ ] `payroll` → **FOR-11** — resource `PAYROLL`

### Already seeded today (no action needed)

- [x] `procurement` → **FOR-07** — resource `DELIVERIES` (already seeded)
- [x] `overview` / `readiness` → resource `PROJECTS` (already seeded)
- [x] `rooms` → resource `ROOMS` (already seeded)
- [x] `estimate` → resource `ESTIMATE` (already seeded)

## Clarification: FOR-05-01 performs NO backend seeding

FOR-05-01 itself introduces **no backend entity, table, migration, or ABAC resource**, and performs
**no backend seeding**:

- No Liquibase changeset.
- No `resources` row.
- No `role_resources` / `role_resource_operations` grant.

This is consistent with FOR-05-01 being a frontend-only spec. All not-yet-seeded tab resources are
owned by the sibling specs listed above and are tracked here so each tab appears correctly once its
resource + role grants are seeded.

_Traceability: Requirements 7.4, 11.5, 11.6._
