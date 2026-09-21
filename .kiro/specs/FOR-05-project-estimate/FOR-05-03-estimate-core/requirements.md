# Requirements Document

FOR-05-03: Estimate core — the `Estimate`, `EstimateLine`, and per-room quantity backbone of a project's kosztorys, PLUS the denormalized per-package project price (a snapshot copied from the catalog that lives its own life, carries a discount placeholder, and keeps its own change history), gated to free editing only while the project is in `DRAFT`, with a new project-scoped `ESTIMATE` ABAC resource

## Introduction

FOR-05-03 is a backend child spec of FOR-05 (Project estimate). It delivers the **core of the
estimate domain** — one `Estimate` per project, its `EstimateLine`s (one per work added to the
project), and the per-room quantity split `EstimateLineRoomQty` from which a line's total quantity
and value are derived — together with the **core of denormalized per-package project pricing**:
when a work is added to the estimate, the system captures a **project price snapshot for every
offer package that exists at add-time** by **copying** the catalog per-package price. That copied
price is **denormalized and lives its own life** from creation: the catalog foreign key is kept for
provenance only, and later catalog edits never change it — mirroring the FOR-04-12b "catalog is a
reference / snapshot copy" contract.

Each per-package project price carries a **discount placeholder** (a denormalized field only, not
the negotiation flow) so consumers can see the original price and the discounted (effective) price
side by side, and FOR-05-03 keeps a **price-history skeleton** so the price's own changes are
queryable. Free editing of prices, lines, and quantities is allowed **only while the project is in
`DRAFT`**; once past `DRAFT` (`ACTIVE` and later) free edits are blocked and subsequent changes go
through the strict amendment procedure owned by a sibling spec. Line values and estimate totals are
**recomputed** from current values, never hand-entered where derivable.

FOR-05-03 introduces a new project-scoped ABAC resource **`ESTIMATE`** and follows the standard
entity-creation checklist (resource seed changeset + ADMIN role grant + `@PermissionResource` on
the controller; project-scoped services implement `ProjectScopedService.getProjectIdPath()`). It
reuses the generic CRUD framework (`AdminController`/`AdminService`, MapStruct, i18n), the FOR-04
catalog (`WorkItem`, `WorkPrice`/`WorkPackagePrice`, `OfferPackage`, `MeasurementUnit`, `Currency`,
`VatRate`), and hard-depends on `Project` (FOR-04-13), `Room` (FOR-04-14), the FOR-05-01 workspace
shell, and FOR-04-11/12b.

This spec deliberately owns only the **substrate** other siblings build on. Temporal price
resolution and the offer freeze (FOR-05-14), the discount negotiation/approval flow (FOR-05-07),
the amendment write-path (FOR-05-08), package VALUE/margin presentation (FOR-05-06), the rich
price-history read-model/UI (FOR-05-15), and material aggregation (FOR-05-05) are **out of scope**
and are called out explicitly in Requirement 12.

The source of truth for the domain is the client's Excel workbook
(`docs/Matrix Foremen v3.0 (1).xlsx`, sheet `Oferta`): one sheet holding the works list, per-room
quantities, three commercial packages (START/COMFORT/PRESTIGE = codes `budget`/`norm`/`lux`), and
values. The FOR-05 parent design (§2 ownership, §4.2 core entities, §4.3a price freeze, §4.5/§4.6
pricing, §5/§9 ABAC) is the authoritative model reference for this spec.

## Glossary

- **Estimate**: The single kosztorys aggregate of a project — one `Estimate` per `Project` (1:1) — carrying the estimate currency, a lifecycle `status`, and computed totals. Modeled per FOR-05 design §4.2.
- **EstimateLine**: One line of the estimate for a single work — references a `WorkItem`, carries a `lineNo`, a `comment`, the work's `unit`, a **denormalized snapshot `unitPrice`**, a derived `quantity`, and a derived `valueNet`.
- **EstimateLineRoomQty**: The per-room quantity for a line (`line` FK, `room` FK, `quantity`); the line's `quantity` is the sum of its room quantities.
- **OfferPackage**: A commercial package START/COMFORT/PRESTIGE, seeded by FOR-04-10 with codes `budget` (START), `norm` (COMFORT), `lux` (PRESTIGE). Display labels come from the package's localized `nameRU`/`namePL`.
- **Catalog per-package price**: The FOR-04-12b `WorkPackagePrice.netPrice` for a `(workItem, offerPackage)` pair, exposed by the catalog to be **read and copied** (never bound by FK for value).
- **MAX-fallback rule**: The FOR-04-12b `EffectivePriceResolver` rule — a package's own `netPrice` if present, else the MAX `netPrice` across the work item's package prices, else unpriced (an empty collection).
- **Per-package project price**: The denormalized, project-owned price snapshot for one `(EstimateLine, OfferPackage)` — copied from the catalog at add-time, carrying an `originalUnitPrice`, a discount placeholder, and a resulting effective `unitPrice`. It holds the catalog FK only as provenance and is never retroactively changed by catalog edits.
- **Snapshot copy / lives its own life**: The FOR-04-12b "catalog is a reference / snapshot copy" contract — a downstream project price is formed by **copying** the catalog value at add-time; the numeric snapshot is the source of truth and later catalog edits never mutate it.
- **Provenance FK**: A foreign key retained only to record which catalog price a snapshot came from; it never drives the stored numeric value.
- **Discount placeholder**: The denormalized discount fields on a per-package project price (`originalUnitPrice`, a discount value + kind, and the resulting effective `unitPrice`) — enough for consumers to show original vs. discounted. The negotiation/approval process is NOT owned here (FOR-05-07).
- **Price-history skeleton**: The FOR-05-03-owned record of changes to a per-package project price (versioned rows or an audited change record) so the price's own history is queryable. The cross-entity comparison view is NOT owned here (FOR-05-15).
- **Editable stage / DRAFT gate**: `Project.status === 'DRAFT'` (or the estimate's `DRAFT` status), during which prices, lines, and quantities are freely editable. **Locked stage**: `ACTIVE` and any later status, in which free edits are blocked.
- **Amendment procedure**: The strict post-`DRAFT` change write-path, owned by FOR-05-08; FOR-05-03 only enforces the `DRAFT` gate and exposes "changes only via amendment" as a boundary.
- **`ProjectScopedService.getProjectIdPath()`**: The FOR-03-04 hook returning the JPA path from an entity to its owning project id, driving automatic project filtering and access guards.
- **Recompute**: Deriving `EstimateLine.quantity`/`valueNet` and `Estimate.totalNet`/`totalVat`/`totalGross` from current values rather than accepting hand-entered figures where they are derivable.

## Requirements

### Requirement 1: Estimate entity (1:1 with Project)

**User Story:** As a project manager, I want each project to have exactly one estimate with its own currency, status, and computed totals, so that the project's kosztorys is a single coherent aggregate.

#### Acceptance Criteria

1. THE system SHALL define an `Estimate` entity associated 1:1 with `Project`, so that a project has at most one estimate and every estimate belongs to exactly one project.
2. THE `Estimate` SHALL carry a `currency` reference (FK to `Currency`) that defaults to PLN when not specified.
3. THE `Estimate` SHALL carry a `status` drawn from the set `DRAFT`, `PRICED`, `APPROVED`, `SIGNED`, defaulting to `DRAFT` on creation.
4. THE `Estimate` SHALL carry computed totals `totalNet`, `totalVat`, and `totalGross`, all derived (Requirement 8) and never hand-entered.
5. WHEN an estimate is requested for a project that has none, THE system SHALL create-or-resolve exactly one `Estimate` for that project rather than creating a second one.
6. IF a caller attempts to create a second `Estimate` for a project that already has one, THEN THE system SHALL reject the attempt and preserve the single-estimate-per-project invariant.

### Requirement 2: EstimateLine with denormalized unit price and derived value

**User Story:** As a designer, I want each work added to the estimate to be a line that references the catalog work but freezes its own unit price, so that the line's value is stable and does not shift when the catalog changes.

#### Acceptance Criteria

1. THE system SHALL define an `EstimateLine` entity that belongs to an `Estimate` and references a `WorkItem` (FK).
2. THE `EstimateLine` SHALL carry a `lineNo`, an optional `comment`, and the work's `unit` (FK to `MeasurementUnit`).
3. THE `EstimateLine` SHALL carry a **denormalized snapshot `unitPrice`** that is the frozen source of truth for the line's value.
4. THE `EstimateLine` SHALL retain the catalog price / `WorkPrice` reference as **provenance only**; the provenance FK SHALL NOT drive the line's numeric value.
5. WHEN the referenced catalog price is later edited or deleted, THE line's snapshot `unitPrice` SHALL remain unchanged (the FOR-04-12b snapshot-copy contract).
6. THE `EstimateLine.quantity` SHALL be **derived** as the sum of its `EstimateLineRoomQty.quantity` values (Requirement 3), and SHALL NOT be hand-entered.
7. THE `EstimateLine.valueNet` SHALL be **derived** as `unitPrice × quantity`, and SHALL NOT be hand-entered.

### Requirement 3: EstimateLineRoomQty (per-room quantities)

**User Story:** As a designer, I want to record how much of a work happens in each room, so that a line's total quantity is the sum across rooms and I can see the per-room breakdown.

#### Acceptance Criteria

1. THE system SHALL define an `EstimateLineRoomQty` entity carrying a `line` (FK to `EstimateLine`), a `room` (FK to `Room`), and a `quantity`.
2. THE `EstimateLineRoomQty.quantity` SHALL be non-negative.
3. THE owning `EstimateLine.quantity` SHALL equal the sum of its `EstimateLineRoomQty.quantity` rows (Requirement 2.6), recomputed whenever a room quantity is added, changed, or removed.
4. WHEN a line has no room quantities, THE derived line `quantity` SHALL be `0` (and its `valueNet` therefore `0`), without error.
5. THE `EstimateLineRoomQty` SHALL be project-scoped via the path `line.estimate.project.id`.
6. WHERE a room referenced by a line's quantity belongs to a different project than the line's estimate, THE system SHALL reject the association so per-room quantities cannot cross projects.

### Requirement 4: Denormalized per-package project price (copied from the catalog at add-time)

**User Story:** As a project manager, I want each work added to the estimate to capture a project price for every commercial package by copying the catalog price, so that the project's per-package prices are fixed at add-time and independent of later catalog edits.

#### Acceptance Criteria

1. WHEN a work (an `EstimateLine`) is added to the estimate, THE system SHALL create a **per-package project price for every `OfferPackage` that exists at add-time**, by **copying** the catalog per-package price from `WorkPackagePrice`.
2. THE per-package project price SHALL be modeled as a per-line-per-package row (an `EstimateLinePackagePrice`-like entity, one row per `(EstimateLine, OfferPackage)`), carrying at least an `originalUnitPrice` and an effective `unitPrice`.
3. WHEN copying a package's catalog price, THE system SHALL apply the FOR-04-12b **MAX-fallback rule**: use the package's own `netPrice` when present, else the MAX `netPrice` across the work item's package prices, else record the package as **unpriced** (no fabricated value).
4. THE per-package project price SHALL retain a catalog **provenance FK** only; the copied numeric value SHALL be the source of truth and SHALL NOT be driven by that FK.
5. WHEN the catalog `WorkPackagePrice` is later edited or deleted, THE already-created per-package project price SHALL remain unchanged (it lives its own life from creation).
6. THE system SHALL pull per-package prices for **all packages that exist at add-time**; the retro-population of per-package project prices for packages **created later** SHALL be OUT OF SCOPE for already-created lines and is deferred as a noted boundary (Requirement 12).
7. WHERE a work item has no catalog package prices at all, THE line's per-package project prices SHALL be recorded as unpriced rather than blocking the line's creation.

### Requirement 5: Discount placeholder on the per-package project price

**User Story:** As a manager reviewing an estimate, I want each per-package project price to show its original price and its discounted price, so that consumers can display both without me running the full approval flow here.

#### Acceptance Criteria

1. THE per-package project price SHALL carry denormalized discount fields sufficient to expose the **original** price and the **effective (discounted)** price: `originalUnitPrice`, a discount value, a discount kind (e.g. `PERCENT`/`ABSOLUTE`), and the resulting effective `unitPrice`.
2. WHERE no discount is applied, THE effective `unitPrice` SHALL equal `originalUnitPrice` (a zero/absent discount is a valid, neutral state).
3. WHEN a discount value and kind are set on a per-package project price, THE effective `unitPrice` SHALL be derived from `originalUnitPrice` and the discount (never hand-entered independently of them).
4. THE discount **negotiation/approval process**, and the `Offer`/`OfferDiscount` entities, SHALL NOT be implemented here; FOR-05-03 owns ONLY the denormalized discount fields on the price (the process is FOR-05-07's — Requirement 12).

### Requirement 6: Price-history skeleton for the per-package project price

**User Story:** As a financier, I want a project price's own changes to be recorded, so that I can query how a per-package project price evolved even before the rich cross-entity history view exists.

#### Acceptance Criteria

1. THE system SHALL keep a record of changes to a per-package project price (versioned rows or an audited change record) so the price's own history is queryable.
2. WHEN a per-package project price's `originalUnitPrice`, discount, or effective `unitPrice` changes, THE system SHALL capture that change in the price-history skeleton.
3. THE price-history skeleton SHALL be the substrate on which the rich "Price history" read-model/UI (FOR-05-15) is later built; FOR-05-03 SHALL guarantee ONLY that project-price changes are captured, NOT the cross-entity comparison view (Requirement 12).

### Requirement 7: DRAFT-only free editing (locked at ACTIVE and beyond)

**User Story:** As a project manager, I want estimate prices, lines, and quantities to be freely editable only while the project is a draft, so that once work starts the numbers are stable and change only through amendments.

#### Acceptance Criteria

1. WHILE the project/estimate is in `DRAFT`, THE system SHALL allow free editing of estimate lines, per-room quantities, and per-package project prices (including the discount fields).
2. WHILE the project is in `ACTIVE` OR any later status, THE system SHALL block free edits to estimate lines, per-room quantities, and per-package project prices.
3. WHEN a free edit is attempted on an estimate past `DRAFT`, THE system SHALL reject it and SHALL indicate that subsequent changes go through the amendment procedure.
4. THE amendment write-path (the post-`DRAFT` change procedure) SHALL NOT be implemented here; FOR-05-03 enforces the `DRAFT` gate and leaves the amendment write-path to FOR-05-08 (Requirement 12).

### Requirement 8: Recompute of line values and estimate totals

**User Story:** As a project manager, I want line values and estimate totals to always reflect the current prices and quantities, so that I never rely on stale or hand-entered figures.

#### Acceptance Criteria

1. THE system SHALL recompute `EstimateLine.valueNet` as `unitPrice × quantity` from current values whenever the line's `unitPrice` or any of its room quantities change.
2. THE system SHALL recompute `Estimate.totalNet` as the sum of its lines' `valueNet`.
3. THE system SHALL recompute `Estimate.totalVat` and `Estimate.totalGross` by applying the applicable VAT rate to the net total (via the project/estimate `VatRate`).
4. THE recomputed totals SHALL never be hand-entered where they are derivable from lines, quantities, and prices.
5. WHEN an estimate has no lines, THE totals `totalNet`, `totalVat`, and `totalGross` SHALL each be `0`, without error.

### Requirement 9: `ESTIMATE` ABAC resource & entity-creation checklist

**User Story:** As a security-conscious maintainer, I want the estimate exposed through a properly seeded, project-scoped ABAC resource, so that access is governed by the permission matrix and the endpoint is neither unguarded nor startup-broken.

#### Acceptance Criteria

1. THE system SHALL introduce a new managed ABAC resource with code `ESTIMATE`, seeded via an idempotent Liquibase changeset (`onFail="MARK_RAN"`) registered last in the changelog, per `.kiro/steering/entity-creation-rules.md`.
2. THE seed SHALL grant the `ADMIN` role the operations the guarded endpoints resolve to (at least `CREATE`/`READ`/`UPDATE`/`DELETE`), also idempotently.
3. THE concrete estimate controller SHALL be annotated `@PermissionResource("ESTIMATE")`, and each in-scope CRUD handler SHALL resolve to a matching `@PermissionOperation` so the application starts (no half-annotated controller).
4. THE estimate services that back project-scoped entities (`Estimate`, `EstimateLine`, `EstimateLineRoomQty`, and the per-package project price) SHALL implement `ProjectScopedService` and override `getProjectIdPath()` with the correct path to the owning project id (`project.id`, `estimate.project.id`, `line.estimate.project.id`, respectively).
5. THE proposed permission matrix for `ESTIMATE` SHALL be: `ADMIN` = CRUD; `MANAGER` = C R U (own); `FOREMAN` = R (own); `FINANCIER` = R (own); `WORKER` = R (own); `CLIENT` = none — stated as the proposed grant, to be finalized in design.
6. WHERE the `ESTIMATE` resource code is already reserved in `NAV_CONFIG`/the parent design, THE seed SHALL reuse that code rather than introducing a duplicate.

### Requirement 10: Reuse of the CRUD framework and FOR-04 catalog

**User Story:** As a maintainer, I want FOR-05-03 to build on the existing framework and catalog rather than reinventing them, so that its footprint stays minimal and consistent with the rest of the system.

#### Acceptance Criteria

1. THE estimate entities SHALL be exposed as generic CRUD resources using the existing `AdminController`/`AdminService` framework, MapStruct DTO mapping, and i18n mapping, without a bespoke controller stack.
2. THE per-package project price SHALL be sourced from the FOR-04-12b catalog by **read + copy** (via the effective-price resolver), and SHALL NOT bind a foreign key to a `WorkPackagePrice` for its numeric value (provenance FK only, Requirement 4).
3. THE spec SHALL reuse `OfferPackage`, `MeasurementUnit`, `Currency`, `VatRate`, and `WorkItem` from FOR-04 rather than redefining them.
4. THE spec SHALL declare hard dependencies on `Project` (FOR-04-13), `Room` (FOR-04-14), the FOR-05-01 workspace shell, and FOR-04-11/12b (work catalog + per-package prices).
5. THE spec SHALL NOT add any material aggregation, package VALUE/margin views, temporal price resolution, offer freeze, discount negotiation, amendment write-path, or price-history UI (all deferred to siblings — Requirement 12).

### Requirement 11: PL/RU i18n parity

**User Story:** As a Polish- or Russian-speaking user, I want any user-facing estimate strings available in both languages, so that the UI is never a mix of both.

#### Acceptance Criteria

1. THE system SHALL provide any user-facing strings (entity display names, statuses, discount kinds, error messages surfaced to users) following the repo `nameRU`/`namePL` convention with PL fallback.
2. WHERE the estimate exposes enumerations to users (e.g. estimate `status`, discount `kind`), THE labels SHALL be defined at PL/RU parity.
3. THE system SHALL NOT surface a raw i18n key or an untranslated identifier to the user for any FOR-05-03 string.

### Requirement 12: Reuse, scope & boundaries (sibling ownership)

**User Story:** As a maintainer coordinating the FOR-05 series, I want FOR-05-03's boundaries with its siblings written down explicitly, so that overlapping concerns (pricing, discounts, amendments, package values, history, materials) do not collide across specs.

#### Acceptance Criteria

1. THE **temporal price selection** `resolveClientPrice(project, work, date)` with `[effectiveFrom, effectiveTo)` intervals, the offer price freeze `OfferPriceSnapshot` at `APPROVED`/`SIGNED`, and the amendment→price-interval linkage SHALL be owned by **FOR-05-14**; FOR-05-03 SHALL provide the denormalized per-package project price + its history skeleton as the substrate FOR-05-14 builds temporality on, and SHALL NOT implement temporal resolution or the offer freeze.
2. THE **discount negotiation/approval flow** and the `Offer`/`OfferDiscount` entities SHALL be owned by **FOR-05-07**; FOR-05-03 SHALL own ONLY the denormalized discount fields on the per-package project price (Requirement 5).
3. THE **amendment write-path** SHALL be owned by **FOR-05-08**; FOR-05-03 SHALL enforce the `DRAFT`-only gate and expose the post-`DRAFT` "changes only via amendment" rule as a boundary (Requirement 7), without implementing the amendment procedure.
4. THE **package VALUE views and margins** (`EstimateLinePackage` value, `EstimateLineMargin`) SHALL be owned by **FOR-05-06**; FOR-05-03 SHALL own the per-package **PRICE** snapshot (the unit price the client pays per package) while FOR-05-06 owns package **VALUE**/margin presentation — the two SHALL NOT overlap.
5. THE **material aggregation / bill of materials** SHALL be owned by **FOR-05-05**; FOR-05-03 SHALL NOT compute materials.
6. THE **rich "Price history" read-model/UI** (the cross-entity comparison view) SHALL be owned by **FOR-05-15**; FOR-05-03 SHALL own ONLY the per-package project price's own change-capture skeleton (Requirement 6).
7. THE **retro-population** of per-package project prices for `OfferPackage`s created **after** a line already exists SHALL be OUT OF SCOPE for FOR-05-03; prices are pulled for all packages that exist at add-time (Requirement 4.6), and retro-population of newly-created packages is deferred as a noted boundary.
