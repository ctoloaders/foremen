# Design — FOR-04-12b: Package-based Work Prices (pivot catalog)

## Overview

FOR-04-12b re-shapes the already-implemented FOR-04-12 `WorkPrice` entity from a
single-price-per-row time-interval model into a **package-based collection
model**. A `WorkPrice` becomes a **per-work-item aggregator**: it keeps its
unique `@ManyToOne workItem` association (at most one `WorkPrice` per work item)
and gains a `@OneToMany` collection `packagePrices` of a new join entity
`WorkPackagePrice`. The price columns (`net_price`, `currency_id`, `valid_from`,
`valid_to`) are **dropped** from `work_prices`; the price now lives on each
`WorkPackagePrice`, which links the aggregator to one `OfferPackage`
(FOR-04-10: `budget`/START, `norm`/COMFORT, `lux`/PRESTIGE) and carries a
`currency` + `netPrice`. A unique constraint on `(work_price_id,
offer_package_id)` guarantees at most one price per package per work.

The catalog **table row stays the `WorkPrice`** (the work item), so the existing
server-side pagination/sort/search over `WorkPrice` rows is preserved. Per-package
prices are surfaced as a **flattened `prices` map on the row DTO** keyed by offer
package `code` → effective price. The frontend renders one pivot column per
seeded package from that map.

Filter and sort by a package price are the fundamentally reworked part of this
spec. They are expressed against a nested **`Pivot_Key`** of the form
`prices.{packageId}.{field}` and resolved server-side through a dedicated
**Custom_Predicate_Flow** (modelled on the *relivent (tickets)* reference
project). Both filter **and** sort are governed by a single managed abstraction —
one registered `CustomQueryResolver` per synthetic leading segment that owns
**both** `toFilter` and `toSort` — so the two paths cannot drift. A path whose
first segment is the literal `prices` is **not** treated as a plain column path by
`SpecificationBuilder`; instead the resolver parses the remaining segments —
`{packageId}` (the offer package's numeric `offerPackage.id`) and `{field}` (a
`WorkPackagePrice` field such as `netPrice`) — JOINs `WorkPrice` onto its
`packagePrices` collection, and builds the compound predicate
`offerPackage.id == {packageId} AND {field} <op> {value}` (filter) or the
discriminated `orderBy` over the same join (sort). The discriminator is the
numeric **`offerPackage.id`**, not the package `code`, because `code` can be
renamed over time while the id is stable. The `WorkPriceService` still owns the
sort **wiring** (it overrides `find`/`findExtended` to detect a synthetic order
and hand it off), but the sort **specifics** — parse, validate, join,
discriminator, `orderBy` — live in the same resolver as the filter, not inline in
the service.

A **max-fallback effective-price resolver** returns a work item's price for a
package: that package's `WorkPackagePrice.netPrice` when present, otherwise the
maximum `netPrice` across the item's `packagePrices` collection; an empty
collection means the item is unpriced. This resolver is a deterministic, total
pure function and is the primary property-based-testing target.

A Liquibase migration re-shapes existing single-price rows into the collection
model (fan-out: one `WorkPackagePrice` per seeded package per current price row),
and the seed is regenerated from the Excel package columns. The `WORK_PRICES`
ABAC resource and its grants are reused unchanged; `WorkPackagePrice` is guarded
as a child of the aggregator through the same resource.

### Catalog is a reference (snapshot copy)

The work-prices catalog is a **reference price book** — the source of truth for
*catalog* prices, but not something downstream consumers bind to by foreign key. A
downstream consumer (a FOR-05 project estimate) forms its own **FIXED** price by
**COPYING** the catalog value as a **snapshot at add-time**; it never holds a
foreign key to a `WorkPackagePrice` row. Two consequences follow, and they are
what makes the cascade deletes below safe:

- Catalog edits/deletes **never** mutate a price already captured in a project —
  the project keeps its own copied value regardless of later catalog changes.
- The catalog is therefore free to change or delete package prices independently;
  no downstream row is invalidated by doing so.

The snapshot entity/columns themselves (how FOR-05 stores its copied price) belong
to **FOR-05**. FOR-04-12b only guarantees the **reference-and-copy contract on the
catalog side**: it exposes catalog prices to be read and copied, and it does not
depend on — nor create — any FK from a consumer back into `work_package_prices`.
Because nothing downstream binds a catalog price, cascading a catalog delete
(whole `OfferPackage` or whole `WorkItem`) cannot orphan a project's captured
price (R1.9, R1.10, R1.11).

### `id` vs. `code` — the deliberate split

Two different identifiers are used for two different concerns, and the split is
intentional and consistent end-to-end:

- **The row DTO `prices` map is keyed by package `code`** (`prices.budget`,
  `prices.norm`, `prices.lux`), a human-stable natural key convenient for the
  frontend to render (`row.prices[code]?.netPrice`) and to compose the
  `Cena {label}` header.
- **The `Pivot_Key` used for filter/sort is keyed by the numeric
  `offerPackage.id`** (`prices.5.netPrice`), because the Custom_Predicate_Flow
  discriminates on `offerPackage.id == {packageId}` and the id survives a `code`
  rename. The frontend gets each package's id (and its localized label) from the
  `/metadata` pivot descriptor, so a column can render from `row.prices[code]`
  while filtering/sorting via `prices.{id}.netPrice`.

The requirements refer to packages by their marketing labels START / COMFORT /
PRESTIGE. The actual `offer_packages.code` values seeded by FOR-04-10 are
`budget` (START), `norm` (COMFORT), `lux` (PRESTIGE). The START/COMFORT/PRESTIGE
text is a display concern rendered from the package's localized `nameRU`/`namePL`.

## Architecture

```mermaid
graph LR
  subgraph Frontend
    WPL[WorkPricesList<br/>DataTable rows = WorkPrice<br/>render row.prices CODE<br/>filter/sort key prices.ID.netPrice] --> API[work-prices-api]
  end
  API -->|GET /api/work-prices<br/>query: prices.ID.netPrice op v<br/>sort: prices.ID.netPrice,dir| WPC[WorkPriceController<br/>@PermissionResource WORK_PRICES]
  WPC --> WPS[WorkPriceService<br/>AdminService<br/>overrides find/findExtended<br/>sort wiring only]
  SB[SpecificationBuilder.buildPredicate<br/>first segment 'prices' -> delegate] -->|toFilter| CQR[CustomQueryResolver prices<br/>owns toFilter + toSort<br/>shared parse/validate/join/discriminator<br/>JOIN packagePrices + distinct<br/>offerPackage.id == ID]
  WPS -->|filter query flows through| SB
  WPS -->|sort override: detect synthetic Order,<br/>partition Pageable, delegate| CQR
  CQR -->|toFilter| FILT[compound predicate:<br/>offerPackage.id == ID AND field op v]
  CQR -->|toSort| SORT[discriminator predicate +<br/>query.orderBy asc/desc join.field]
  WPS --> RADS[ReadOnlyAdminService.find<br/>buildFinalSpecification<br/>findAll spec, pageable]
  WPS --> MAP[WorkPriceServiceMapper<br/>@AfterMapping builds prices map<br/>via EffectivePriceResolver]
  MAP --> EPR[EffectivePriceResolver<br/>pure max-fallback fn]
  WPS --> DAO[(work_prices<br/>1:N work_package_prices)]
```

- **Row/paging unit** is `WorkPrice`. The read machinery
  (`AdminController.find` → `ReadOnlyAdminService.find` → `buildFinalSpecification`
  → `findAll(spec, pageable)`) is preserved; pagination stays over distinct
  `WorkPrice` rows.
- **One unified resolver.** Both the pivot filter and the pivot sort delegate to
  the **same** registered `CustomQueryResolver` for `(WorkPriceEntity, "prices")`,
  which owns both `toFilter` and `toSort` and shares one private
  parse/validate/join/discriminator helper. Filter and sort therefore build on
  the identical `packagePrices` JOIN, `offerPackage.id == {packageId}`
  discriminator, and id/field validation, and cannot diverge.
- **Pivot filter** rides the existing query grammar (`?query=`) but branches out
  of the default single-column path resolution. When `SpecificationBuilder`
  resolves a filter token whose first path segment is `prices`, it delegates to
  the resolver's `toFilter`, which JOINs `packagePrices` (a collection → JOIN +
  `query.distinct(true)`) and emits `offerPackage.id == {packageId} AND
  {field} <op> {value}` on that single join alias.
- **Pivot sort** cannot ride the standard `sort` param straight through: Spring
  Data JPA resolves `Sort.Order` properties against the entity metamodel and
  would reject the synthetic `prices.{id}.{field}`. So `WorkPriceService`
  overrides `find`/`findExtended` **only to wire the sort**: it detects a
  synthetic pivot order, partitions it out of the `Pageable` (so nothing synthetic
  reaches the metamodel), and delegates the order to the resolver's `toSort`. The
  resolver — not the service — parses the segments, validates id+field, JOINs
  `packagePrices`, restricts it to `offerPackage.id == {packageId}`, and applies
  `query.orderBy(cb.asc/desc(join.get(field)))`, returning the discriminator
  predicate for the service to compose.
- **Prices map** is assembled per row by the service mapper's `@AfterMapping`,
  which iterates the aggregator's `packagePrices` and, for each seeded package,
  calls the `EffectivePriceResolver` to fill `prices[code]`.

## Components and Interfaces

### New: `WorkPackagePriceEntity` (`dao/model`) — `@Table("work_package_prices")` extends `BaseEntity`
```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "work_price_id", nullable = false)
private WorkPriceEntity workPrice;

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "offer_package_id", nullable = false)
private OfferPackageEntity offerPackage;

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "currency_id", nullable = false)
private CurrencyEntity currency;

@Column(name = "net_price", nullable = false)
private BigDecimal netPrice;
```

**FK cascade (DB-level, R1.5, R1.10, R1.11).** The two *owner* FKs of
`work_package_prices` are declared `ON DELETE CASCADE` at the database level via
Liquibase (`<addForeignKeyConstraint ... onDelete="CASCADE"/>`):
`work_price_id → work_prices(id)` (`fk_work_package_prices_work_price`) and
`offer_package_id → offer_packages(id)` (`fk_work_package_prices_offer_package`).
The `currency_id → currencies(id)` FK (`fk_work_package_prices_currency`) keeps the
**default `RESTRICT`** — currencies are reference data, not owners of the price
row, and a currency in use must stay protected (R1.11).

The two owner cascades are a **database-level guarantee**, deliberately distinct
from JPA cascade: the `orphanRemoval`/`CascadeType.ALL` on the aggregator's
`packagePrices` collection (below) handles *aggregate-level* deletes routed
**through** the `WorkPrice` aggregator, whereas the DB `ON DELETE CASCADE` handles
**whole-`OfferPackage`** deletes (a delete of an `offer_packages` row cleans every
referencing `work_package_prices` row across the catalog, R1.10) and
**whole-`WorkItem`** deletes (see the aggregator FK below, R1.9) — including a raw
SQL / dictionary delete that never passes through JPA. Because catalog prices are
reference-only (no downstream FK binds them, per the "Catalog is a reference"
note), cascading their deletion is safe.

### Changed: `WorkPriceEntity` — aggregator
- **Remove** `currency`, `netPrice`, `validFrom`, `validTo` (R1.3, R1.6).
- Keep `@ManyToOne workItem` and make it **unique** (R1.1): add
  `@JoinColumn(name="work_item_id", nullable=false, unique=true)` backed by a
  DB unique constraint from the migration. The `work_item_id → work_items(id)` FK
  (`fk_work_prices_work_item`) is declared **`ON DELETE CASCADE`** at the DB level
  via Liquibase (`<addForeignKeyConstraint ... onDelete="CASCADE"/>`), so deleting
  a `WorkItem` removes its `WorkPrice` aggregator, which in turn — via the
  `ON DELETE CASCADE` on `work_package_prices.work_price_id` above — removes all of
  that aggregator's `WorkPackagePrice` rows (R1.9). This cascade is a DB-level
  guarantee that fires even for a raw SQL / dictionary delete of a `work_items`
  row outside JPA.
- **Add** the owned collection (R1.2):
  ```java
  @OneToMany(mappedBy = "workPrice", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<WorkPackagePriceEntity> packagePrices = new ArrayList<>();
  ```
  `@OneToMany` to a managed entity is what lets both the query layer and
  `EntityMetadataResolver` treat `packagePrices` as a join-able collection whose
  element carries `offerPackage`/`currency`/`netPrice` leaves. Note the JPA
  `orphanRemoval`/`CascadeType.ALL` here covers only aggregate-level deletes routed
  through the aggregator; the whole-`OfferPackage` and whole-`WorkItem` cases rely
  on the DB `ON DELETE CASCADE` FKs described above.

### New: `EffectivePriceResolver` (`service/pricing/EffectivePriceResolver`)
A stateless Spring `@Component` (and equivalently a pure static helper for the
property test) implementing the max-fallback rule over a `WorkPrice`'s
collection. It operates on already-loaded `WorkPackagePrice` data (no I/O), so it
is a pure function of `(collection, packageCode)` and is deterministic (R3.5).

```java
/** @return the effective price for {@code packageCode}, or empty when unpriced. */
Optional<BigDecimal> resolve(Collection<WorkPackagePriceEntity> packagePrices, String packageCode);
```
Rule (R3.2–R3.4, R3.6, R3.7):
1. If a member's `offerPackage.code == packageCode` → return its `netPrice`.
2. Else if the collection is non-empty → return `max(netPrice)` over members.
3. Else (empty collection) → `Optional.empty()` (unpriced).

Because branch 1 returns an actual member price and branch 2 returns the max,
the result is always `<= max(netPrice)` (R3.7); a single-member collection
returns that one price for every package (R3.6, covered by branch 1 for its own
package and branch 2 for all others). The resolver is keyed by `code` because it
serves the row DTO's code-keyed `prices` map; it is unrelated to the id-keyed
Pivot_Key used for filter/sort (see the id-vs-code split above).

### Custom_Predicate_Flow — the pivot filter/sort mechanism (R4)

This is the centerpiece of the rewrite. It replaces the old approach (controller
rewriting `prices.{code}.netPrice` into two `offerPackage.code`-discriminated
tokens). The flow is keyed by `offerPackage.id` and covers **both** filter and
sort against a `Pivot_Key` `prices.{packageId}.{field}` (R4.1).

Filter and sort are governed by **one managed abstraction**, not two ad-hoc code
paths: a single registered resolver owns both capabilities and shares one internal
parse/validate/join/discriminator helper, so the two cannot drift.

#### Unified resolver contract — `CustomQueryResolver`

A leading synthetic segment is owned by exactly one `CustomQueryResolver`, a small
interface that exposes **both** the filter and the sort capability for that
segment:

```java
interface CustomQueryResolver<T> {
    // leading segment this resolver owns for the entity, e.g. "prices"
    String property();

    // FILTER: build the compound predicate for a parsed synthetic filter token
    Specification<T> toFilter(QueryToken.Filter filter);

    // SORT: build a predicate + orderBy contribution for a parsed synthetic order.
    // Applies query.orderBy(...) as a side effect and returns the discriminator predicate.
    Specification<T> toSort(Sort.Order order);
}
```

Both methods route through the **same** private helper on the resolver that:
splits the `Pivot_Key` into `["prices", "{packageId}", "{field}"]`, parses and
validates `packageId` (existing `offerPackage.id`) and `field` (a filterable/
sortable `WorkPackagePrice` field), calls `getOrCreateJoin(root, "packagePrices")`
(a collection → JOIN, reused so filter and sort on the same package id collapse
onto one alias), sets `query.distinct(true)`, and produces the
`offerPackage.id == {packageId}` discriminator predicate. `toFilter` then ANDs the
value predicate onto that discriminator; `toSort` applies the `orderBy` on the
join and returns the discriminator. Because both capabilities share this helper,
filter and sort use identical parsing, identical id/field validation (identical
errors), and the identical join + discriminator — they are one managed mechanism,
not two.

**Registry.** Resolvers are held in a tiny registry keyed by
`(entityClass, leadingSegment)`. This spec adds exactly one registration,
`(WorkPriceEntity, "prices") → CustomQueryResolver`. The registry is consulted by
`SpecificationBuilder` for the filter half and by `WorkPriceService` for the sort
half, so both halves resolve to the **same** resolver instance.

#### Where `prices` is recognized as the synthetic pivot property

The flow must know that a leading `prices` segment is a synthetic
(non-column) collection pivot **for `WorkPrice` specifically**, and which real
collection (`packagePrices`) it maps to. Two placement options were weighed:

- **(a) Generic pluggable registry in `SpecificationBuilder`.** Add a small
  registry keyed by `(entityClass, leadingSegment)` → a `CustomQueryResolver`.
  `SpecificationBuilder.buildPredicate` checks, before `resolvePath`, whether the
  filter field's first segment matches a registered custom-query property for
  the query's entity class; if so it delegates to that resolver's `toFilter`
  instead of the default single-column path.
- **(b) WorkPrice-specific override at the service layer.** Detect `prices.`
  tokens in `WorkPriceService` and supply a custom `Specification` via the
  `addRequiredQuery()` / `buildFinalSpecification(String)` seam, bypassing
  `SpecificationBuilder` for those tokens.

**Chosen for the filter half: (a), implemented as a minimal generic branch inside
`SpecificationBuilder.buildPredicate`, backed by the registry, with the unified
`CustomQueryResolver` registered for `(WorkPriceEntity, "prices")`.** The sort
half keeps a `WorkPriceService.find`/`findExtended` override as its wiring seam
(retained per the user's decision), but that override **delegates** the actual
sort construction to the same registered resolver's `toSort` — it does not
re-implement parsing/join/orderBy locally. Rationale for (a) on the filter half:

- It keeps the pivot resolution inside the one place that already turns a filter
  token into a `Specification` (`buildPredicate`), so the existing
  `QueryParser` → `SpecificationBuilder` pipeline, boolean composition
  (`AND`/`OR`, parentheses), and the `?query=` request contract are all reused
  unchanged. The parsed `QueryToken.Filter` already carries `field`, `operator`,
  and `value` — exactly what the compound predicate needs.
- It preserves paging over `WorkPrice` rows (the query root stays `WorkPrice`; the
  collection JOIN sets `distinct(true)` — see below).
- It is minimally invasive: `SpecificationBuilder` stays generic (it does **not**
  hard-code `WorkPrice` or `prices`), and no shared read method is overridden for
  filtering. The one registration entry
  `(WorkPriceEntity, "prices") → CustomQueryResolver` is the only WorkPrice-specific
  knowledge, and it lives next to the resolver, not in the shared translator.

Tradeoff noted: this teaches the shared `SpecificationBuilder` a new concept (a
custom-predicate branch). It is kept generic via the registry rather than a
`WorkPrice`/`prices` literal, so other entities can register their own synthetic
pivots later without touching the translator again. We deliberately stop at a
single-segment leading key + one resolver per `(entity, segment)` and do not
build a broader DSL — that would be over-engineering for one pivot.

Option (b) was rejected as a way to *build* the filter because a query-string
mutation cannot express the `offerPackage.id == {packageId} AND netPrice <op>
value` conjunction on a *single shared join alias* cleanly, and re-parsing
rewritten tokens loses the guarantee that both predicates hit the same joined
`WorkPackagePrice` row. The service override seam **is** still used for the sort
half — see below — because sort is a `Pageable` concern that never reaches
`SpecificationBuilder`; but that override is pure **wiring** (detect synthetic
order → partition the `Pageable` → call the resolver → compose → delegate) and the
sort semantics themselves live in the same resolver as the filter, so the two
never diverge.

#### Filter resolution (R4.2, R4.3)

`SpecificationBuilder.buildPredicate(QueryToken.Filter filter, Class<T> entityClass)`
gains a guard: if `entityClass`+first path segment are registered, it delegates to
`resolver.toFilter(filter)`, which (via the shared parse/validate/join/
discriminator helper) returns a `Specification<T>`:

```
// resolver.toFilter(filter): shared helper parses + validates + joins + discriminates
segments   = filter.field().split("\\.")   // ["prices", "5", "netPrice"]
packageId  = Long.parseLong(segments[1])    // 5  (validated, see errors)
field      = segments[2]                     // "netPrice" (validated)

Specification:
  Join<WorkPriceEntity, WorkPackagePriceEntity> join =
      getOrCreateJoin(root, "packagePrices");   // collection → JOIN, reused (shared helper)
  query.distinct(true);                         // no WorkPrice row multiplication
  Predicate pkg   = cb.equal(join.get("offerPackage").get("id"), packageId);  // shared helper
  Predicate value = buildCriteriaPredicate(join.get(field), filter.operator(),
                                           filter.value(), cb);   // reuse existing op switch
  return cb.and(pkg, value);
```

So `prices.5.netPrice > 10` becomes
`join.get("offerPackage").get("id") == 5 AND join.get("netPrice") > 10` on one
`packagePrices` join (R4.2). Reusing `getOrCreateJoin` means two pivot filters on
the *same* package id share one join, while filters on *different* package ids get
distinct joins (each with its own discriminator) — the correct semantics for
"filter by START price AND by PRESTIGE price". `buildCriteriaPredicate` and
`convertValue` already coerce the `value` to the field's Java type (`BigDecimal`
`netPrice` → numeric compare), so all comparison operators (`>`, `<`, `>=`, `<=`,
`==`, ranges via `AND`) work with no new operator code (R4.3).

#### Sort resolution (R4.4, R4.5)

Sort is a **managed** capability of the same unified resolver as the filter — the
resolver owns `toSort` alongside `toFilter` — but the *wiring* that hands a
synthetic sort order to that resolver stays a `WorkPriceService` override, because
sort is a `Pageable` concern that never reaches `SpecificationBuilder` (retained
per the user's decision to keep the sort specifics reached via a service
override).

Sort arrives as the standard Spring Data `?sort=prices.5.netPrice,asc` and reaches
`ReadOnlyAdminService.find` as a `Sort.Order` whose `getProperty()` is the raw
`prices.5.netPrice`. `processSort` is a passthrough for `WorkPrice` (empty i18n
set), and Spring Data JPA would then reject the synthetic property against the
metamodel. So `WorkPriceService` **overrides** `find(Pageable, String)` and
`findExtended(Pageable, String)` (both are `default` methods on
`ReadOnlyAdminService`, hence overridable) to contribute **only the wiring** —
detect, partition, delegate, compose, delegate to the inherited read:

1. **Detect** synthetic orders: partition `pageable.getSort()` into orders whose
   property first segment is a registered custom-query property for the entity
   (here `prices`) and ordinary orders. The check is against the registry, so the
   service does not hard-parse `prices.` itself.
2. **Partition the `Pageable`**: rebuild it with **only the ordinary orders** so
   nothing synthetic reaches Spring Data JPA's metamodel resolution.
3. **Delegate** each synthetic order to the same registered resolver's
   `resolver.toSort(order)`. The resolver — not the service — parses
   `prices.{id}.{field}`, validates id + field (via the shared helper, identical
   to the filter), creates/reuses the `packagePrices` join, sets
   `query.distinct(true)`, adds the `offerPackage.id == {packageId}` discriminator,
   and applies `query.orderBy(cb.asc/desc(join.get(field)))` as a side effect —
   returning the discriminator `Specification`:
   ```
   // inside resolver.toSort(order) — SAME shared helper as toFilter:
   parse property → packageId, field   (same parse + validation as toFilter)
   Join join = getOrCreateJoin(root, "packagePrices");   // reused with the filter join
   query.distinct(true);
   // restrict the join to the requested package so the order is over that
   // package's price only (R4.4)
   Predicate pkg = cb.equal(join.get("offerPackage").get("id"), packageId);
   query.orderBy( order.getDirection().isAscending()
                   ? cb.asc(join.get(field)) : cb.desc(join.get(field)) );
   return pkg;   // discriminator predicate returned; orderBy applied as a side effect
   ```
   Because the resolver reuses `getOrCreateJoin`, a pivot filter and a pivot sort
   on the same package id collapse onto the same alias.
4. **Compose**: AND each returned discriminator `Specification` onto
   `buildFinalSpecification(rawQuery)`.
5. **Delegate** to the inherited read flow with the cleaned `Pageable` and the
   composed `Specification`, i.e. call `super`-equivalent logic (re-implement the
   two-line body: `findAll(spec, cleanedPageable)` then map) — mirroring how
   `ProjectController` overrides `find` to delegate to a bespoke service method.

So the service supplies **only** the wiring (detect synthetic order → partition
`Pageable` → call resolver → compose → delegate); **all** the pivot semantics
(parse, validate, join, discriminator, `orderBy`) live in the shared resolver.
Filter and sort are thus governed by one managed mechanism and cannot drift.

Because the query root and page unit remain `WorkPrice` and the collection join is
`distinct`, paging is over distinct `WorkPrice` rows and no work item is split or
duplicated across pages (R4.5, R5.8). The `orderBy` is expressed on the JPA
`CriteriaQuery`, which Spring Data preserves alongside its own pagination.

> Extension-point note. The generic layer offers **no** seam that translates a
> synthetic sort property automatically: `processSort` only appends i18n locale
> suffixes and Spring Data JPA resolves `Sort.Order` properties against the
> metamodel (rejecting `prices.5.netPrice`). So the concrete `WorkPriceService`
> overrides `find`/`findExtended` to **detect-and-delegate**: it detects a
> synthetic order and hands it to the shared `CustomQueryResolver.toSort`. The
> specifics of *building* the sort (parse, validate, join, discriminator,
> `orderBy`) live in the resolver — the same one that builds the filter — while
> the override supplies only the wiring. This service-override seam is retained
> per the user's decision; it is the same shape `ProjectController` already uses to
> specialize a read, and it is called out here so it can be revisited if a generic
> synthetic-sort seam is ever added to `ReadOnlyAdminService`.

#### Package-id validation and errors (R4.6)

Both `toFilter` and `toSort` run the **same** id/field validation through the
resolver's shared parse/validate helper, so filter and sort report **identical**
errors for the same bad `Pivot_Key`:

- **Unknown `offerPackage.id`** (segment 2 is not an existing package id): throw
  `ForemenApiException(HttpStatus.BAD_REQUEST, "error.workprices.unknown.package",
  packageId)`. Existence is checked against a small `SeededOfferPackages` provider
  now **keyed by id** (it loads the seeded `offer_packages` `(id, code, label)`
  once and caches them), reused for both validation and the `/metadata`
  descriptors. (A JPA metamodel/`EXISTS` check on `offer_packages` is an
  equivalent alternative; the cached provider avoids a per-request query.)
- **Unknown/invalid segment-3 field** (not a filterable/sortable
  `WorkPackagePrice` field — e.g. `prices.5.bogus`): throw a descriptive
  client-error. Reuse `error.query.invalid.field.path` (the same identifier
  `SpecificationBuilder.resolvePath` already raises for bad paths) so the error
  vocabulary stays consistent; the allowed segment-3 fields are the scalar/
  reference leaves of `WorkPackagePriceEntity` (`netPrice`, and any other
  filterable field the metadata advertises). Attempting `join.get(field)` for an
  unknown attribute would itself raise `IllegalArgumentException`; the resolver
  catches it and maps to the client error rather than a 500.

#### Metadata (R4.7)

`GET /api/work-prices/metadata` is served by the generic
`AdminController.getMetadata()` via `EntityMetadataResolver.resolve(WorkPriceEntity.class)`.
Because `WorkPriceEntity` now has the `@OneToMany packagePrices` collection whose
element carries `@ManyToOne offerPackage`/`currency` and a `netPrice`, the resolver
already emits a `packagePrices` field with nested reference/scalar leaves. That
generic descriptor is *not* the pivot contract, though — the frontend needs one
descriptor **per seeded package, keyed by `offerPackage.id`**.

So `WorkPriceController` overrides `getMetadata()` in a thin wrapper: it calls
`EntityMetadataResolver.resolve(WorkPriceEntity.class)`, then appends one synthetic
pivot field per seeded package from the id-keyed `SeededOfferPackages` provider:

- `name = "prices.{id}.netPrice"` (the exact `Pivot_Key` the frontend sends back
  as filter/sort), `dataType = NUMBER`, `sortable`/`filterable` true;
- carrying the package's numeric `id` and its localized label
  (`nameRU`/`namePL`) so the frontend can build the `Cena {label}` header and know
  which id to use in the Pivot_Key.

This augmentation is id-keyed throughout: the descriptor's field name embeds the
id, and the payload includes the id + label (the `code` is a display convenience
the frontend already has from the row DTO map, not part of the Pivot_Key).

### `WorkPriceServiceModel` (list model) — add the prices map
- **Remove** `currencyId`, `currencyCode`, `netPrice`, `validFrom`, `validTo`,
  `current`.
- Keep `id`, `workItemId`, `workItemName`.
- **Add** `Map<String, PackagePrice> prices` where
  `PackagePrice { Long offerPackageId; String currencyCode; BigDecimal netPrice; }`
  (R5.1). **Keys are offer package `code`s** (the display key); each entry
  carries the numeric `offerPackageId` too, so the frontend can pair the
  code-keyed cell with the id-keyed Pivot_Key. A key is present for a package only
  when the resolver returns a value (unpriced packages omit the key, R5.6).

### `WorkPriceServiceMapper` — build the prices map
- Drop the currency/date/`current` mappings.
- Keep `workItemId`, the `@AfterMapping resolveReferencedNames` for
  `workItemName`. (`getI18nSupportedProperties()` stays empty for `WorkPrice`, so
  i18n filter/sort resolution remains passthrough — the pivot flow does its own
  routing.)
- Add an `@AfterMapping` that, given the seeded package codes (from the
  `SeededOfferPackages` provider), fills `target.prices`:
  ```java
  for (OfferPackageEntity pkg : seededPackages) {
      resolver.resolve(source.getPackagePrices(), pkg.getCode())
          .ifPresent(price -> target.getPrices().put(
              pkg.getCode(), toPackagePrice(pkg.getId(), source, pkg.getCode(), price)));
  }
  ```
  `currencyCode` per entry comes from the matching member's `currency.code`, or
  from the max-member's currency when falling back; `offerPackageId` is the
  seeded package id (the id-side of the split).

### Controller DTOs
- `WorkPriceDtoModel` → `{ id, workItemId, workItemName, Map<String,PackagePriceDto> prices }`
  with `PackagePriceDto { Long offerPackageId, String currencyCode, BigDecimal netPrice }`
  (map keyed by `code`; each value carries `offerPackageId`).
- `WorkPriceDtoExtendedModel` (single/detail + create/update) →
  `{ id, workItemId, List<PackagePriceUpsert> packagePrices }` where
  `PackagePriceUpsert { Long offerPackageId, Long currencyId, BigDecimal netPrice }`.
- `WorkPriceCreateRequest` / `WorkPriceUpdateRequest` →
  `{ @NotNull Long workItemId, @NotEmpty List<@Valid PackagePriceUpsert> packagePrices }`,
  each `PackagePriceUpsert` with `@NotNull offerPackageId`, `@NotNull currencyId`,
  `@NotNull @Positive netPrice`.
- Create/Update responses mirror the extended DTO.

### `WorkPriceController` — pivot wiring
The controller keeps `@PermissionResource("WORK_PRICES")` and the 10-generic
`AdminController` wiring (R7.1). Its only additions are:

- `getMetadata()` override to append the id-keyed per-package pivot descriptors
  (R4.7, above).
- No `addCustomQueryCondition` rewrite is needed for the pivot filter — the filter
  `Pivot_Key` flows through `?query=` unchanged and is branched inside
  `SpecificationBuilder`, which delegates to the registered
  `CustomQueryResolver.toFilter`.
- The synthetic-sort **wiring** lives on `WorkPriceService.find`/`findExtended`
  (above), which detects a synthetic order and delegates its construction to the
  same `CustomQueryResolver.toSort`; the controller's inherited
  `find`/`findExtended` need not change and it may be left as the thin generic
  implementation.

`SeededOfferPackages` is a small Spring bean that loads the seeded
`offer_packages` rows once (cached) as `(id, code, nameRU, namePL)`, used for
(1) Pivot_Key id validation, (2) the `/metadata` pivot descriptors, and (3) the
mapper's per-package iteration.

### Frontend — `foremen-frontend/src/features/work-prices/`
- **types**: replace scalar price fields on `WorkPriceDto` with
  `prices: Record<string, { offerPackageId: number; currencyCode: string; netPrice: number }>`
  (map keyed by package `code`). Extended/create/update carry
  `packagePrices: PackagePriceUpsert[]`.
- **WorkPricesList**: build columns dynamically — a static `workItem` reference
  column plus **one pivot column per seeded package** (from the `/metadata` pivot
  descriptors), each:
  - **render key = package `code`**: `render: (_v, row) => row.prices[code]?.netPrice ?? '—'`
    (R5.4–R5.6);
  - **filter/sort key = the id-keyed Pivot_Key** `field: 'prices.{id}.netPrice'`,
    `dataType: 'number'`, `sortable: true`, `filterable: true` (R5.7). The `{id}`
    comes from the metadata descriptor for that package; the cell body reads the
    code-keyed map. This is the id-vs-code split at the UI: **column renders
    `row.prices[code]`, but its DataTable filter/sort key is `prices.{id}.netPrice`**;
  - header `Cena {label}` where `{label}` is the package's active-language label
    from the metadata descriptor (R5.3, R10.1).
  Pagination/sort/search remain over `WorkPrice` rows (R5.8, R9.2). Filtering/
  sorting a pivot column issues the server request keyed by the column's id-keyed
  `prices.{id}.netPrice` Pivot_Key (R9.2).
- **WorkPriceFormSheet**: one row per seeded package (offerPackage fixed label +
  currency select + netPrice input); submitting sends the `packagePrices` array.
- **i18n** (`workPrices.*`, PL+RU parity, R9.3, R10.2): the pivot column header is
  composed as `t('workPrices.table.priceColumn') + ' ' + packageLabel`, i.e. the
  localized `Cena` prefix (Requirement 10.1) plus the package's active-language
  label (single active language, R10.3). This spec introduces **no new i18n keys
  beyond the `Cena` prefix already covered by Requirement 10**; obsolete
  `netPrice/validFrom/validTo/current/badge.*` keys that are no longer rendered are
  removed, and an empty-value placeholder string is reused/added only as
  Requirement 10 covers.

## Data Models

### `work_package_prices` (new table, R1.5, R1.7)
| Column | Type | Constraints |
|---|---|---|
| `id` | BIGSERIAL | PK |
| `work_price_id` | BIGINT | NOT NULL, FK `fk_work_package_prices_work_price` → `work_prices(id)` **ON DELETE CASCADE** (R1.5) |
| `offer_package_id` | BIGINT | NOT NULL, FK `fk_work_package_prices_offer_package` → `offer_packages(id)` **ON DELETE CASCADE** (R1.5, R1.10) |
| `currency_id` | BIGINT | NOT NULL, FK `fk_work_package_prices_currency` → `currencies(id)` (**RESTRICT / no cascade**, R1.11) |
| `net_price` | NUMERIC(12,2) | NOT NULL |
| `created_date`/`created_by`/`updated_date`/`updated_by` | audit | per `BaseEntity` |
| — | UNIQUE `uk_work_package_prices_work_offer` on `(work_price_id, offer_package_id)` | R1.7 |

### `work_prices` after migration (R1.6)
`id`, `work_item_id` (NOT NULL, now UNIQUE via `uk_work_prices_work_item`), audit
columns. `net_price`, `currency_id`, `valid_from`, `valid_to` are **dropped**. The
`work_item_id` FK (`fk_work_prices_work_item` → `work_items(id)`) is
**`ON DELETE CASCADE`** (R1.9), so deleting a `WorkItem` drops this aggregator row
and — via the cascade on `work_package_prices.work_price_id` — all of its package
prices.

### Liquibase changesets (registered LAST in `changelog.xml`, after `043`)
1. **`044-migrate-work-prices-to-packages.xml`** (R2):
   - `044a` de-duplicate to one aggregator per work item: delete `work_prices`
     rows where `valid_to IS NOT NULL` (keep the current row). Guard
     `preConditions` on `valid_to` column existing (`columnExists`) + `MARK_RAN`
     (R2.1).
   - `044b` create `work_package_prices` table with the three FKs (R2.2), guard
     `not tableExists` + `MARK_RAN`. The two **owner** FKs are declared
     `onDelete="CASCADE"` (`<addForeignKeyConstraint ... onDelete="CASCADE"/>`):
     `fk_work_package_prices_work_price` (`work_price_id → work_prices(id)`) and
     `fk_work_package_prices_offer_package`
     (`offer_package_id → offer_packages(id)`) (R1.5, R1.10). The
     `fk_work_package_prices_currency` FK (`currency_id → currencies(id)`) is
     created **without** `onDelete` (default `RESTRICT`, R1.11).
   - `044c` fan-out: for each retained current `work_prices` row, insert one
     `work_package_prices` row per **existing** offer package (every row in
     `offer_packages`, discovered dynamically — **no hard-coded package codes**)
     with `net_price`/`currency_id` copied from the source row (R2.3, R2.5):
     ```sql
     INSERT INTO work_package_prices (work_price_id, offer_package_id, currency_id, net_price, created_date, created_by)
     SELECT wp.id, op.id, wp.currency_id, wp.net_price, NOW(), 'system'
     FROM work_prices wp
     CROSS JOIN offer_packages op
     WHERE NOT EXISTS (SELECT 1 FROM work_package_prices x
                       WHERE x.work_price_id = wp.id AND x.offer_package_id = op.id);
     ```
     The `CROSS JOIN offer_packages op` (unfiltered) fans each current price out
     across **all** packages present in the dictionary, so adding or renaming a
     package needs no changeset edit. The `NOT EXISTS` guard ensures at most one
     row per `(work_price_id, offer_package_id)` before the unique constraint
     (R2.5).
   - `044d` add UNIQUE `uk_work_package_prices_work_offer` on `(work_price_id,
     offer_package_id)`; guard on constraint absence + `MARK_RAN`.
   - `044e` add UNIQUE `uk_work_prices_work_item` on `work_prices(work_item_id)`
     (R1.1), guarded.
   - `044f` drop `valid_from`, `valid_to`, `net_price`, `currency_id` from
     `work_prices` (R1.6, R2.4); guard each `columnExists` + `MARK_RAN`.
   - `044g` ensure `fk_work_prices_work_item` (`work_prices.work_item_id →
     work_items(id)`) is **`ON DELETE CASCADE`** (R1.9). The FOR-04-12 FK does not
     carry the cascade, so this sub-step **drops the existing FK and recreates it**
     with `<addForeignKeyConstraint ... onDelete="CASCADE"/>` (or adds it with the
     cascade if absent). Kept idempotent with `preConditions` — guard the
     `dropForeignKeyConstraint` on the FK existing and guard the
     `addForeignKeyConstraint` on it not already being present — both
     `onFail="MARK_RAN"`, so a re-run against an already-cascaded database changes
     nothing.
   - All sub-changesets idempotent (R2.6).
2. **`045-seed-work-package-prices.xml`** (R6): regenerate per-package seed from
   the Excel `PAKIET START`/`PAKIET COMFORT`/`PAKIET PRESTIGE` columns.
   - Ensure one aggregator per work item (insert `work_prices(work_item_id)` when
     absent), guarded (R6.2).
   - For each package column with a parseable positive price, insert one
     `work_package_prices` row resolving `work_item_id` via `name_pl` +
     `work_category_id`, `offer_package_id` via
     `(SELECT id FROM offer_packages WHERE code=...)`, `currency_id` via
     `(SELECT id FROM currencies WHERE code='PLN')` (R6.1, R6.3, R6.6).
   - Blank/zero/non-parseable package column → no row for that pair (R6.4).
   - When the Excel row has only the legacy `CENA` and no per-package columns,
     fan that price into **every existing offer package** — discovered from
     `offer_packages` at seed time (no hard-coded codes), consistent with the
     044 migration fan-out's unfiltered `CROSS JOIN offer_packages` (R6.5).
     (Today that resolves to `budget`/`norm`/`lux`; the current Excel export has
     `PAKIET *` columns mostly `0,00`, so in practice most rows take this legacy
     fan-out path.)
   - Idempotent `preConditions onFail="MARK_RAN"` with `sqlCheck` guarding both
     aggregator and package-price re-insertion (R6.7).
   - Registered after `044` (R6.8).

   **Seed generation**: a throwaway Python script parses the CSV, emits the
   `<insert>`/`<sql>` blocks, then is deleted. It skips category-header rows
   (integer LP), blank rows, and rows without a real ZAKRES/JM. Single quotes in
   `name_pl` are escaped (`''`). Duplicate `name_pl` within a category is
   de-duplicated (consistent with FOR-04-12). Parsed/skipped counts documented in
   the changeset comment.

### ABAC (R7 — unchanged)
No new resource. `WORK_PRICES` and its grants (ADMIN CRUD; MANAGER
CREATE/READ/UPDATE; FOREMAN/WORKER/FINANCIER READ; CLIENT none) remain as seeded
by `039`. `WorkPackagePrice` has no controller of its own; it is written only
through the `WorkPriceController` aggregator and thus guarded by `WORK_PRICES`
(R7.2, R7.3). `WorkPrice` is a global catalog aggregator — **not** project-scoped,
so neither it nor `WorkPackagePrice` implements `ProjectScopedService`; the unified
`CustomQueryResolver` (filter via `SpecificationBuilder`, sort via the service
override's delegation) is the only Specification source `WorkPriceService`
contributes.

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

The `EffectivePriceResolver` is a pure, total function over `(packagePrices
collection, requested package code)`. It is the primary property-based-testing
target; the schema, migration, seed, Custom_Predicate_Flow pivot filter/sort
wiring, and UI are validated by integration/example tests (see Testing Strategy)
rather than properties, since they exercise the DB/Spring/rendering layers and
one-time migration behavior rather than input-varying pure logic.

### Property 1: Resolver case-correctness (exact / max-fallback / unpriced)

*For any* `WorkPackagePrice` collection and *any* requested offer package `code`:
if the collection contains a member for that `code`, the resolver returns that
member's `netPrice`; else if the collection is non-empty, it returns the maximum
`netPrice` across the collection; else (empty collection) it reports the item as
unpriced (no value).

**Validates: Requirements 3.1, 3.2, 3.3, 3.4**

### Property 2: Single-member collection is uniform across packages

*For any* collection holding exactly one `WorkPackagePrice` and *any* offer
package `code`, the resolver returns that single member's `netPrice`.

**Validates: Requirements 3.6**

### Property 3: Effective price never exceeds the collection maximum

*For any* non-empty `WorkPackagePrice` collection and *any* requested offer
package `code`, the resolved effective price is less than or equal to the maximum
`netPrice` in the collection.

**Validates: Requirements 3.7**

### Property 4: Resolution is deterministic

*For any* `WorkPackagePrice` collection and *any* requested offer package `code`,
resolving twice yields identical results.

**Validates: Requirements 3.5**

## Error Handling

- **Unknown pivot package id** (R4.6): filtering/sorting by
  `prices.{packageId}.{field}` where `{packageId}` is not a seeded
  `offer_packages.id` throws `ForemenApiException(HttpStatus.BAD_REQUEST,
  "error.workprices.unknown.package", packageId)`, surfaced by the existing
  `@RestControllerAdvice` as a localized `ErrorResponse` (PL/RU message keys added
  to `messages*.properties`). Validated against the id-keyed `SeededOfferPackages`
  provider.
- **Invalid pivot field / query grammar / field path** (R4.6): a non-existent
  segment-3 `WorkPackagePrice` field (e.g. `prices.5.bogus`) raises a
  client-error reusing `error.query.invalid.field.path` (the same identifier
  `SpecificationBuilder.resolvePath` already emits), rather than a 500. Ordinary
  invalid query grammar remains handled by `QueryParser`/`SpecificationBuilder`
  (`error.query.invalid.field.path`, `error.query.invalid.date`).
- **Create/Update validation**: bean-validation on the request
  (`@NotNull workItemId`, `@NotEmpty packagePrices`, each entry
  `@NotNull offerPackageId/currencyId`, `@NotNull @Positive netPrice`) →
  `BAD_REQUEST` via the standard validation handler.
- **Duplicate package for one aggregator**: the `(work_price_id,
  offer_package_id)` unique constraint rejects a second price for the same
  package under one work item; the resulting `DataIntegrityViolationException`
  maps to a `CONFLICT`/`BAD_REQUEST` `ErrorResponse` (existing handler).
- **Unpriced work item**: not an error — the row DTO simply omits keys for
  packages with no effective price; the UI shows an empty-value placeholder
  (R5.6).
- **Deleting an in-use `OfferPackage` or `WorkItem`**: now **succeeds** rather than
  failing with an FK violation. The `ON DELETE CASCADE` on
  `work_package_prices.offer_package_id` (R1.10) and on
  `work_prices.work_item_id` / `work_package_prices.work_price_id` (R1.9) cleans
  the referencing catalog rows automatically, so no application-level pre-deletion
  or error path is needed — and because catalog prices are reference-only
  (snapshot-copied downstream), no consumer is invalidated.
- **Deleting a `Currency` in use**: still **protected**. The
  `fk_work_package_prices_currency` FK keeps the default `RESTRICT` (R1.11), so a
  delete of a currency still referenced by a `work_package_prices` row fails with
  an FK violation exactly as before (mapped to a `CONFLICT`/`BAD_REQUEST`
  `ErrorResponse` by the existing handler).
- **Migration idempotency**: every `044`/`045` sub-changeset carries a
  `preConditions ... onFail="MARK_RAN"` guard, so re-runs against an
  already-migrated database make no changes (R2.6, R6.7).

## Testing Strategy

Follows the workspace test-execution standard: run only the affected classes with
`--tests`, redirect to a temp log, and read the JUnit XML for pass/fail. Do not
run the full suite.

**Dual approach**: property tests for the pure resolver logic; integration and
example tests for schema/migration/seed, the Custom_Predicate_Flow pivot query
wiring, metadata, ABAC, and the frontend.

### Property-based tests (backend, jqwik 1.9.2, `@Property(tries = 100)`)
`EffectivePriceResolverPropertyTest` implements the four Correctness Properties
with a single property test each, over a jqwik generator of `WorkPackagePrice`
collections (varying size incl. empty and singleton, varying `offerPackage.code`
from the seeded set plus absent codes, varying positive `netPrice`) and a
requested package `code`. Each test is tagged, e.g.:
`@Tag("Feature: FOR-04-12b-work-prices-packages, Property 1: Resolver case-correctness (exact / max-fallback / unpriced)")`.
The resolver is exercised as a pure function (no persistence), so 100+ iterations
are cheap.

### Backend integration / example tests
- `WorkPriceControllerIntegrationTest` (R8.2): persist a `WorkItem` + `Currency`
  + `OfferPackage`s and a `WorkPrice` with `packagePrices`; assert the list DTO
  exposes the `prices` map keyed by package `code` (each entry carrying its
  `offerPackageId`); assert the `(work_price_id, offer_package_id)` unique
  constraint rejects a duplicate package price for the same aggregator.
- `WorkPricePivotQueryIntegrationTest` (R8.3): seed several work items with
  differing package prices, then exercise the id-keyed Custom_Predicate_Flow:
  - a filter `prices.{id}.netPrice <op> {value}` produces the compound predicate
    `offerPackage.id == {id} AND netPrice <op> {value}` and returns only matching
    `WorkPrice` rows;
  - a sort `prices.{id}.netPrice,{dir}` orders `WorkPrice` rows by that package's
    price via the same discriminated join;
  - pagination over `WorkPrice` rows is correct (more rows than page size → no
    work item split or duplicated across pages);
  - both the filter and the sort resolve through the **same** unified
    `CustomQueryResolver` (`toFilter` / `toSort`) sharing one parse/validate/join/
    discriminator helper, so an unknown `offerPackage.id` and an unknown segment-3
    field produce **identical** client errors whether supplied as a filter or as a
    sort;
  - an unknown `offerPackage.id` → client error `error.workprices.unknown.package`
    (identical for filter and sort);
  - an unknown segment-3 field (e.g. `prices.{id}.bogus`) → client error
    `error.query.invalid.field.path` (identical for filter and sort).
- `WorkPricePackagesMigrationIntegrationTest` (R8.4): assert `work_package_prices`
  exists with its three FKs, and specifically that the **`ON DELETE CASCADE`** FKs
  are present — `work_package_prices.work_price_id`
  (`fk_work_package_prices_work_price`), `work_package_prices.offer_package_id`
  (`fk_work_package_prices_offer_package`), and `work_prices.work_item_id`
  (`fk_work_prices_work_item`) — while `work_package_prices.currency_id` remains
  `RESTRICT`; the fan-out created one `WorkPackagePrice` per seeded package for
  each pre-existing current work price; `valid_from`/`valid_to` and the moved
  `net_price`/`currency_id` are absent from `work_prices`; the
  `(work_price_id, offer_package_id)` unique constraint exists; package prices are
  seeded (COUNT > 0, currency PLN); and re-running the changelog is a no-op
  (idempotency). The cascade-FK assertion reads the DB catalog (e.g. the FK's
  `delete_rule` / `confdeltype`) to confirm `CASCADE` versus `RESTRICT`.
- `WorkPriceCascadeDeleteIntegrationTest` (R8.5): with no application-level
  pre-deletion, verify the DB `ON DELETE CASCADE` behavior directly:
  - deleting an `OfferPackage` removes **exactly** the `WorkPackagePrice` rows that
    referenced it **across the whole catalog** (all aggregators), and leaves every
    other package's rows untouched (R1.10);
  - deleting a `WorkItem` removes its `WorkPrice` aggregator **and** all of that
    aggregator's `WorkPackagePrice` rows (R1.9), while other work items'
    aggregators and package prices are untouched;
  - as a negative control, deleting a `Currency` still referenced by a
    `WorkPackagePrice` **fails** (FK `RESTRICT`, R1.11), confirming the currency FK
    does not cascade.
- ABAC (R7): the existing `WorkPricesResourceSeedIntegrationTest` continues to
  assert grants unchanged (ADMIN CRUD / MANAGER CREATE,READ,UPDATE /
  FOREMAN,WORKER,FINANCIER READ / CLIENT none); application startup exercises
  `PermissionAnnotationValidator` for the annotated controller.

### Frontend tests (Vitest + RTL)
- `WorkPricesList.test.tsx` (R9.1, R9.2): renders one pivot column per seeded
  package built from the row DTO's `prices` map, each headed `Cena {label}` in the
  active language; shows the empty-value placeholder for an unpriced row; asserts
  pagination/sort/search operate on `WorkPrice` rows and that filtering/sorting a
  pivot column issues the request with the column's id-keyed
  `prices.{id}.netPrice` Pivot_Key (while the cell renders from the code-keyed
  `row.prices[code]`).
- i18n parity (R9.3, R10.2): assert the `workPrices.*` key sets are at parity in
  `pl.json` and `ru.json` and no raw key renders.

PBT is intentionally **not** applied to the schema, migration, seed, pivot query
(DB/Spring/JPA wiring), metadata serialization, or UI rendering — those are
one-time or framework/IO-bound behaviors best covered by the integration and
example tests above.
