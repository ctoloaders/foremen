# Requirements Document

## Introduction

This spec (**FOR-11-04-shipment-tracking-and-schedule**) delivers the **delivery half** between "the
supplier order is paid" and "the goods are on site". FOR-11-03 ends at `SUPPLIER_PAID` and explicitly
leaves `IN_DELIVERY` to this spec. Today the buyer tells the foreman about a delivery by phone, nobody
knows whether a parcel is late, and the foreman learns that materials arrive after the works started
only when the crew stands idle. This spec adds:

1. **Shipment** (new entity `Shipment`, with lines per position and a small event history): one
   dispatch of goods for one project, covering whole or partial quantities of one or several positions
   (optionally tied to one external order). Creating a shipment moves its `SUPPLIER_PAID` positions to
   `IN_DELIVERY`.
2. **Delivery methods:** `CARRIER` (carrier code + tracking number), `SHOP_DELIVERY` (the shop's own
   delivery, with the shop-order link) and `PHONE_CONTACT` (only a driver/contact phone).
3. **Generic carrier-tracking service:** an SPI with one adapter per carrier that resolves a
   normalised status and ETA from the carrier's **public tracking page** (no per-carrier contract),
   a **scheduler 4 times per working day**, a rate-limited **manual refresh**, back-off, caching and a
   **disabled fallback to manual data**. A **short spike per carrier** decides which adapters ship.
4. **Manual entry** of delivery date/time window and driver contact (always possible, wins over the
   carrier for the ETA until the user reverts).
5. **ETA propagation:** the shipment ETA feeds `plannedDeliveryDate` through the existing
   `PlannedDeliveryDateSynchronizer`, so the FOR-11-00 `upcoming` endpoint, widgets and planner show it.
6. **Materials vs works:** a read-only overlay on the project Gantt (manager: order-by dates; foreman:
   arrival vs works start) and a **deadline sweep** that detects missed order-by dates and arrival after
   the dependent works start.
7. **Foreman "upcoming deliveries" screen** (mobile list over the extended `upcoming` endpoint).
8. **Four notifications** through the generic service (emit-only).

### Product decisions this spec implements (from `../OVERVIEW.md` and the product owner)

- Carrier status comes from **each carrier's public tracking page** (no contract); the service must back
  off, cache, never hammer and degrade to manual data. Carriers' terms of use are **not verified** and are
  reviewed in the spike (Requirement 8).
- Scheduler: **4 times per working day**, working hours only, configurable; terminal shipments skipped.
- **Manual refresh** per shipment is rate-limited. **Manual entry** (date/time, driver contact) is the
  fallback and also covers shop delivery and phone-only delivery.
- Normalised carrier statuses: `CREATED`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED`, `EXCEPTION`,
  `UNKNOWN`, plus the last-event time.
- Scope of carriers: InPost, DPD, DHL, GLS, UPS, FedEx, Poczta Polska / Pocztex, Orlen Paczka; others
  later (one carrier = one adapter).
- A position becomes `IN_DELIVERY` **only through this spec** (FOR-11-03 Requirement "out of scope").
- One request can become several shipments; a position can be split across shipments by quantity.
- Shipments hold **no money**: no shipment endpoint exposes a price or cost field.

### Facts verified in the code (basis of the design)

- `PositionTransitionPolicy` already has the SYSTEM edge `SUPPLIER_PAID -> IN_DELIVERY` and the receiving
  chain `IN_DELIVERY <-> PARTIALLY_RECEIVED`, `-> RECEIVED`. **No code drives `SUPPLIER_PAID -> IN_DELIVERY`
  today.** There is **no edge back** from `IN_DELIVERY` to `SUPPLIER_PAID` (needed for a cancelled
  shipment; added here, Requirement 6).
- `PlannedDeliveryDateSynchronizer` is the single writer of `PurchasePositionEntity.plannedDeliveryDate`
  and only mirrors the request's `expectedDeliveryDate`. FOR-11-00 documents that FOR-11-04 calls the
  same component with a shipment ETA. A request-date change must **not** overwrite a shipment ETA.
- `UpcomingDeliveriesService.upcoming(days, projectId)` and `GET .../upcoming`
  (`PURCHASE_POSITIONS` READ) return `UpcomingDeliveryDto` (no money). **No frontend code calls it yet.**
- Positions have `needByDate`, `orderByDate`, `plannedDeliveryDate` and a nullable `workCategory`
  (`work_category_id`, changeset 233). `ProjectScheduleBarEntity` holds `startHalfDays` (1-based,
  half-day grid from the project start date), so the date a category's works start is
  `projectStartDate + ((startHalfDays + 1) / 2 - 1)` days (earliest bar of the category).
- The repository has the SPI + disabled-fallback pattern (`NipLookupService` + `DisabledNipLookupService`
  with a `@Conditional`, `BankFeedConnectorRegistry`), the sweep pattern (`@Scheduled(cron =
  "${...:-}")`, default `-` = disabled) and `Clock` seam (`ProcurementClockConfig`). There is **no
  ShedLock**, no working-hours config and no holiday calendar.
- Highest changeset is **240**; this spec uses **241 to 243**.
- External orders (`ExternalOrderEntity` / `ExternalOrderLineEntity`) carry per-position partial
  quantities and order-level `expectedShipDate` / `expectedDeliveryDate`. The `externalOrder.paid`
  notification already tells the buyer to "fill in the delivery" (this spec is that step).

### Scope boundaries

- **In scope:** the `Shipment` domain (entity, lines, events), delivery methods, the carrier SPI,
  registry and the adapters that pass the spike, scheduler, manual refresh, manual entry, ETA
  propagation, position-stage effects (`SUPPLIER_PAID -> IN_DELIVERY` and the new reversal edge),
  the extended `upcoming` endpoint, the Gantt materials overlay (backend endpoint + frontend toggle),
  the deadline sweep, ABAC resource and grants, dataset, four notifications, frontend (shipments list
  and form, foreman upcoming screen, Gantt overlay), locale keys, tests and `test-cases.md`.
- **Out of scope:**
  - goods receipt, accepted / missing / returned quantities, the quantity ledger, photos, and opening a
    new shipment for missing or returned units (FOR-11-05); `DELIVERED` on a shipment is the **carrier's
    claim** and does **not** change the position (only receipt does);
  - client visibility of shipments, tracking or delivery dates (client-safe surface is not extended);
  - carrier contracts, label printing, booking of shipments, courier APIs requiring credentials
    (an optional token adapter such as the InPost API stays future work behind the same SPI);
  - e-mail / SMS / Telegram delivery of notifications (generic service, in-app only);
  - an automatic action on a late shipment or a missed order-by date (flags and notifications only);
  - a public-holiday calendar (the scheduler runs on weekdays only; holidays are future work);
  - changing the works schedule, the external-order lifecycle or the payment gate;
  - construction-material purchases of FOR-06-03/04/05 (unchanged).

### Notifications (emit-only; to be confirmed with the requirements review)

This spec **only emits** notifications through the generic notification service (in-app bell) and
implements no delivery channel. The four types come from the OVERVIEW candidate list ("confirmed" in
revision 5). Message text is the frontend title of the type (`t(type)`, PL/RU in Requirement 23); the
body is `null`. Every type carries a deep link, so every notification is clickable.

| Type | Recipient | Trigger | Deep link | PL title | RU title |
|------|-----------|---------|-----------|----------|----------|
| `notification.position.orderBy` | ACTIVE `MANAGER` members of the project | a position's `orderByDate` is reached (today or earlier) while it is still before `ORDERED` (deadline sweep) | `/deliveries?project={projectId}` | Termin zamówienia materiału minął | Наступил срок заказа материала |
| `notification.shipment.shipped` | ACTIVE `FOREMAN` and `MANAGER` members of the project, minus the actor | a shipment was created (goods dispatched) | foreman: `/deliveries/upcoming?shipment={id}`; manager: `/deliveries/shipments?shipment={id}` | Dostawa w drodze | Поставка в пути |
| `notification.shipment.delayed` | ACTIVE `FOREMAN` and `MANAGER` members of the project, minus the actor | the ETA of an open shipment moved **later** (by the carrier or by hand) | same as `shipped` | Dostawa opóźniona | Поставка задерживается |
| `notification.shipment.downtimeRisk` | ACTIVE `FOREMAN` and `MANAGER` members of the project, minus the actor | the ETA is **after the start of a dependent works category** (once per distinct ETA value) | same as `shipped` | Ryzyko przestoju: materiał dotrze po starcie prac | Риск простоя: материал придёт после начала работ |

Rules shared by all four: a recipient is notified at most once per event; the actor is never notified;
every emission is wrapped so a failure is logged and never rolls back or blocks the triggering
operation (the FOR-11-03 `ProformaOrderNotificationEmitter` pattern). `notification.position.orderBy`
is addressed to the project manager only, as confirmed in the OVERVIEW; adding the assigned buyer is a
one-line recipient change if the product owner wants it.

### i18n decisions

- **Backend entity fields requiring i18n:** only the seeded `resources` row of `SHIPMENTS`
  (`name_ru`/`name_pl`, `description_ru`/`description_pl`). `Shipment`, its lines, events, notes,
  tracking numbers, links and driver contacts are **user or system data**: no RU/PL variants. No new
  `roles` and no new `delivery_statuses` rows are seeded (`in_delivery` exists from FOR-11-00). Carriers
  are **codes** in an SPI registry, not a dictionary table; their display names are frontend keys.
- **Backend messages:** every new `error.shipment.*` key exists in `messages.properties` (PL, default)
  and `messages_ru.properties` (RU), checked by a parity test.
- **Frontend:** every new string is a key in both `pl.json` and `ru.json` at parity: groups
  `shipments.*`, `upcomingDeliveries.*`, `scheduleOverlay.*`, `shipments.carrier.*`, the nav labels and
  the four `notification.*` titles (all listed with values in the design). Enum labels (delivery method,
  shipment status, ETA source, overlay risk) are frontend keys.

## Glossary

- **Shipment**: one dispatch of goods for one project, with a delivery method, status, ETA and lines.
- **Shipment_Line**: a position and the quantity of it carried by the shipment.
- **Active_Shipment**: a shipment whose status is neither `DELIVERED` nor `CANCELLED`.
- **Delivery_Method**: `CARRIER`, `SHOP_DELIVERY` or `PHONE_CONTACT`.
- **Carrier_Code**: the code of an adapter in the registry (`INPOST`, `DPD`, `DHL`, `GLS`, `UPS`,
  `FEDEX`, `POCZTA_POLSKA`, `ORLEN_PACZKA`) or `OTHER` (no adapter, manual only).
- **Tracking_Adapter**: the SPI implementation for one carrier (number pattern, public page, parser).
- **Tracking_Result**: normalised outcome of one adapter call: status, last-event time, optional ETA.
- **Normalised_Status**: `CREATED`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED`, `EXCEPTION`,
  `UNKNOWN`. A shipment additionally has the manual-only `CANCELLED`.
- **ETA**: the shipment's expected delivery date, with an optional time window.
- **ETA_Source**: `TRACKING` (set by the carrier adapter) or `MANUAL` (set by a user; the carrier no
  longer overwrites it until the user reverts).
- **Degraded**: a carrier shipment whose automatic tracking keeps failing (back-off applied); the
  shipment stays usable with manual data.
- **Works_Start**: the earliest start date of the bars of the position's work category.
- **Downtime_Risk**: the ETA of the position is after its Works_Start.
- **Order_By_Missed**: `orderByDate` is today or earlier and the position is before `ORDERED`.
- **Today**: the current date in `Europe/Warsaw`.

## Requirements

### Requirement 1: ABAC resource and grants

**User Story:** As an administrator, I want shipments guarded by their own permission resource, so that
the buyer and manager manage them and the foreman only reads them.

#### Acceptance Criteria

1. A seed changeset SHALL add the resource `SHIPMENTS` to `resources` (RU/PL name and description),
   guarded by an idempotent precondition, registered last in `changelog.xml` (entity-creation checklist).
2. A second changeSet SHALL grant: `ADMIN` CRUD; `BUYER` CRUD; `MANAGER` READ + UPDATE (manual entry,
   cancel, refresh); `FOREMAN` READ; `FINANCIER` READ. `CLIENT` and `WORKER` SHALL get **no** grant.
   Re-running the changesets SHALL insert nothing twice.
3. The same changeset SHALL grant `FOREMAN` nothing else: the foreman keeps reading `upcoming` through
   the existing `PURCHASE_POSITIONS` READ grant.
4. The concrete controller SHALL carry `@PermissionResource("SHIPMENTS")`; the `upcoming` endpoint and the
   overlay endpoint SHALL stay on `PURCHASE_POSITIONS` READ through `@RequiresPermission`.
5. A project-scoped shipment SHALL be guarded by project membership through the service's
   `getProjectIdPath()` (`"project.id"`); `BUYER` and `FINANCIER` are company-wide through the existing
   `ProcurementProjectAccess` (no change there).

### Requirement 2: Data model

**User Story:** As a developer, I want a small, clean shipment model, so that FOR-11-05 can build on it.

#### Acceptance Criteria

1. Changeset 241 SHALL create `shipments`: `project_id` (required), `external_order_id` (nullable),
   `delivery_method` (`CARRIER` | `SHOP_DELIVERY` | `PHONE_CONTACT`, CHECK), `carrier_code` (varchar 30,
   nullable), `tracking_number` (varchar 64, nullable), `tracking_url` (varchar 1000, nullable, manual
   link for `OTHER`), `shop_order_link` (varchar 1000, nullable), `status` (CHECK of the seven values,
   default `CREATED`), `status_source` (`TRACKING` | `MANUAL`), `eta_date` (date, nullable),
   `eta_time_from` / `eta_time_to` (time, nullable), `eta_source` (`TRACKING` | `MANUAL`),
   `driver_name` (varchar 200), `driver_phone` (varchar 40), `note` (varchar 2000), `shipped_at`,
   `delivered_at` (timestamptz), `last_event_at`, `last_checked_at`, `next_check_at` (timestamptz),
   `consecutive_failures` (int, default 0), `downtime_notified_for` (date), and audit columns.
2. Changeset 241 SHALL create `shipment_lines`: `shipment_id`, `position_id`, `quantity` (14,3, `> 0`),
   unique `(shipment_id, position_id)`; and `shipment_events`: `shipment_id`, `status` (CHECK),
   `occurred_at`, `source` (`TRACKING` | `MANUAL` | `SYSTEM`), `note` (varchar 255), `created_at`.
   Indexes SHALL cover `(project_id, status)`, `(status, next_check_at)`, `(position_id)` and
   `(shipment_id, occurred_at)`.
3. Changeset 242 SHALL seed the resource and grants (Requirement 1). Changeset 243 SHALL add
   `purchase_positions.order_by_notified_for` (date, nullable), the one-shot marker of Requirement 14.
4. All tables SHALL be registered last in `changelog.xml`; every changeset SHALL be idempotent in the
   style of 229 and 239. **Shipped quantity is derived** (Σ of active line quantities per position); no
   column is added to the position for it.
5. A shipment SHALL belong to exactly one project; every line's position SHALL belong to the same
   project. When `external_order_id` is set, the order SHALL belong to that project.

### Requirement 3: Delivery methods and field rules

**User Story:** As a buyer, I want to record how the goods travel, so that the right contact or link is
available to the foreman.

#### Acceptance Criteria

1. `CARRIER` SHALL require `carrierCode` and `trackingNumber`. `carrierCode` SHALL be a registry code or
   `OTHER`; an unknown code SHALL be rejected (`error.shipment.carrier.unknown`). `OTHER` requires a
   `trackingUrl` or accepts none (manual-only shipment).
2. `SHOP_DELIVERY` SHALL accept a `shopOrderLink` and driver fields; `PHONE_CONTACT` SHALL require at
   least a `driverPhone`. Neither requires a carrier or tracking number.
3. A tracking number SHALL be trimmed, upper-cased and stripped of spaces; the accepted alphabet is
   `[A-Z0-9-]` with length 4 to 40 (`error.shipment.trackingNumber.invalid`). A number that does not
   match the adapter's own pattern SHALL be **accepted with a warning flag** `trackingNumberSuspect`
   (the adapter may still resolve it), not rejected.
4. URLs (`shopOrderLink`, `trackingUrl`) SHALL be absolute `http`/`https` URLs up to 1000 characters;
   other schemes are rejected (`error.shipment.link.invalid`).
5. `driverPhone` SHALL accept digits, spaces, `+`, `-`, `(`, `)` with 5 to 40 characters.
6. Fields that do not belong to the chosen method SHALL be ignored on write and returned as `null`.
7. The tracking page link for a `CARRIER` shipment SHALL be derived by the adapter
   (`trackingPageUrl(number)`) or taken from `trackingUrl` for `OTHER`, and exposed read-only so the user
   can always open the carrier's own page.

### Requirement 4: Create a shipment

**User Story:** As a buyer, I want to register a dispatch for the positions I ordered, so that the foreman
and manager see it coming.

#### Acceptance Criteria

1. `POST` create SHALL accept: `projectId`, optional `externalOrderId`, the method fields,
   optional `etaDate` / time window, driver fields, `note` and `lines[]` (`positionId`, `quantity`).
   It SHALL require `SHIPMENTS` CREATE and project access.
2. Every line's position SHALL be `AGREED` or `PACKAGE` in `SUPPLIER_PAID`, `IN_DELIVERY` or
   `PARTIALLY_RECEIVED`, or `CLIENT_OWN` in `IN_DELIVERY` or `PARTIALLY_RECEIVED`; any other stage SHALL be
   rejected with `error.shipment.position.notShippable` naming the position.
3. When `externalOrderId` is given, each line's position SHALL be covered by a line of that order and the
   line quantity SHALL NOT exceed the order line's quantity minus the quantity already on other active
   shipments of the same order (`error.shipment.line.exceedsOrderLine`).
4. For every position, the Σ of its active shipment line quantities (including this one) SHALL NOT exceed
   its **shippable cap**: `orderedQuantity` for `AGREED`/`PACKAGE`, `quantity` for `CLIENT_OWN`
   (`error.shipment.line.exceedsCap`). Quantities have scale 3 and SHALL be `> 0`; duplicate positions in
   one request are rejected.
5. The shipment SHALL be saved with status `CREATED`, `status_source = MANUAL`, `shipped_at = now`, an
   initial `shipment_events` row, and `next_check_at = now` for a trackable carrier shipment.
6. After saving, for each `SUPPLIER_PAID` position the service SHALL call
   `PositionLifecycleService.transition(positionId, IN_DELIVERY, SYSTEM)`; positions already in
   `IN_DELIVERY` / `PARTIALLY_RECEIVED` are left as they are. The whole create runs in one transaction.
7. When `etaDate` is supplied it SHALL be stored with `eta_source = MANUAL`; otherwise the ETA stays empty
   until a tracking result provides it.
8. A shipment created for a trackable carrier SHALL trigger an **immediate first refresh** after commit
   (Requirement 10 rules apply, failures are swallowed).
9. The service SHALL publish a `ShipmentCreatedEvent` (actor, project, shipment id, position ids) used by
   Requirements 11 and 15.

### Requirement 5: Edit and manual entry

**User Story:** As a buyer or manager, I want to enter or correct delivery data by hand, so that a failing
carrier page never blocks the foreman.

#### Acceptance Criteria

1. `PUT` SHALL update the method fields, ETA date, time window, driver fields, links, note and **manual
   status** (any of `CREATED`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED`, `EXCEPTION`), requiring
   `SHIPMENTS` UPDATE. Lines SHALL be editable only while the shipment is `CREATED`; quantity rules of
   Requirement 4 apply.
2. Setting `etaDate` / time window by hand SHALL set `eta_source = MANUAL`. A dedicated action
   `POST .../eta/revert` SHALL set `eta_source = TRACKING`, clear the manual ETA and trigger a refresh
   (carrier shipments only).
3. Setting the status by hand SHALL set `status_source = MANUAL` and add a `MANUAL` event. A later
   successful tracking result SHALL overwrite a manual status **only if** it is a forward move or
   `status_source` is `TRACKING`; a `DELIVERED` result always wins.
4. Setting `DELIVERED` (by hand or by tracking) SHALL stamp `delivered_at` and stop scheduling. It SHALL
   **not** change any position (FOR-11-05 owns receipt).
5. A `CANCELLED` shipment and a `DELIVERED` shipment SHALL be read-only except for `note`
   (`error.shipment.terminal`).
6. Changing the method SHALL clear fields of the other methods and reset tracking state
   (`next_check_at`, `consecutive_failures`).
7. Every change SHALL write an audit snapshot (as for `PurchasePosition`).

### Requirement 6: Cancel a shipment and reverse the position stage

**User Story:** As a buyer, I want to cancel a shipment I registered by mistake, so that the positions go
back to "paid, not shipped".

#### Acceptance Criteria

1. `POST .../cancel` SHALL require `SHIPMENTS` UPDATE and a non-terminal shipment; it SHALL set status
   `CANCELLED`, write an event and clear `next_check_at`.
2. The position machine SHALL gain one SYSTEM edge `IN_DELIVERY -> SUPPLIER_PAID` for `AGREED` and
   `PACKAGE` (the policy table and its tests are updated; no other edge changes).
3. After cancelling, for each line's position that has **no other Active_Shipment**, whose status is
   `IN_DELIVERY` and whose `acceptedQuantity = 0`, the service SHALL apply
   `transition(.., SUPPLIER_PAID, SYSTEM)`. `CLIENT_OWN` positions and positions with receipts are not
   moved back.
   *Delivered shipment guard (clarification):* a position that has a `DELIVERED` shipment is also not moved
   back, because it then awaits receipt in `IN_DELIVERY`. The same rule applies when lines are removed from a
   `CREATED` shipment (Requirement 4).
4. Recomputing `plannedDeliveryDate` for the affected positions SHALL follow Requirement 11.
5. A cancelled shipment SHALL stay visible in lists (filter by status) and in the position's shipment
   history; physical delete is not offered.

### Requirement 7: Carrier-tracking service (SPI)

**User Story:** As a developer, I want one generic tracking service with one adapter per carrier, so that
carriers can be added or replaced without touching shipments.

#### Acceptance Criteria

1. The SPI `CarrierTrackingAdapter` SHALL expose: `code()`, `isEnabled()`, `matches(String number)`
   (soft pattern check), `trackingPageUrl(String number)` and
   `TrackingResult fetch(String number) throws TrackingUnavailableException`.
   `TrackingResult` SHALL hold a Normalised_Status, `lastEventAt`, optional `etaDate`, and an optional
   short `eventText` (max 255 characters, used for the event note).
2. A `CarrierTrackingRegistry` (modelled on `BankFeedConnectorRegistry`) SHALL resolve an adapter by
   carrier code and list the codes available; an unknown or disabled code resolves to **empty**, which
   means "manual only".
3. Each adapter SHALL be enabled by `foremen.procurement.tracking.carriers.<code>.enabled` (default
   `false`, real boolean defaults so an empty environment value cannot break binding). A disabled or
   missing adapter SHALL never be called; the shipment shows the carrier link and accepts manual data.
4. The master switch `foremen.procurement.tracking.enabled` (default `false`) SHALL disable every adapter
   and the scheduler; this is the shipped state until the spike results are accepted.
5. **Outbound safety:** adapters SHALL call only hard-coded carrier hosts over `https`, URL-encode the
   tracking number, apply connect and read timeouts (default 5 s / 10 s), cap the response size
   (default 1 MB), send an identifying `User-Agent`, follow no redirect to another host, and never log
   response bodies. A user-supplied URL is **never** fetched.
6. **Back-off and caching:** per adapter the service SHALL enforce a minimum interval between two outbound
   calls (default 2 s), and per shipment SHALL not fetch more often than the minimum shipment interval
   (default 30 minutes) even when asked, except for a manual refresh (Requirement 10). After a failure
   `consecutive_failures` SHALL grow and `next_check_at` SHALL be pushed out exponentially (1 h, 2 h, 4 h,
   capped at 24 h); after 5 consecutive failures the shipment is `Degraded` (flag in the DTO) and keeps
   being retried at the cap.
7. A normalised failure SHALL be classified (`NOT_FOUND`, `RATE_LIMITED`, `BLOCKED`, `PARSE_ERROR`,
   `TIMEOUT`, `OTHER`). `RATE_LIMITED` and `BLOCKED` SHALL pause **that carrier** for the cool-down
   (default 1 h) for all shipments. `NOT_FOUND` for a number younger than 24 h SHALL be treated as "not
   yet registered" (status stays `CREATED`, no failure counted).
8. A `TrackingResult` SHALL be applied by `ShipmentTrackingService.apply(shipment, result)`, which updates
   status (Requirement 5.3), `last_event_at`, `last_checked_at`, the ETA (only when `eta_source =
   TRACKING`), appends a `TRACKING` event **only when the status changed**, resets
   `consecutive_failures`, and publishes ETA-change events (Requirement 11).
9. The service SHALL never throw out of the scheduler or the refresh endpoint: failures become
   `last_checked_at` / `consecutive_failures` updates and a log line without the number's owner data.

### Requirement 8: Carrier spike and adapter set

**User Story:** As a product owner, I want each carrier checked once for the least fragile public
mechanism, so that only reasonable adapters ship.

#### Acceptance Criteria

1. For each carrier of the scope list the implementation SHALL perform a short spike and record in
   `carrier-spike.md` (this spec folder): the mechanism found (public JSON endpoint behind the tracking
   page, documented unauthenticated endpoint, or page parsing), the URL pattern, the tracking-number
   pattern, sample responses (with numbers redacted), robots / terms-of-use observations, the observed
   rate limits and a **verdict**: `ADAPTER` (ship an adapter) or `MANUAL_ONLY` (link-out + manual data).
2. An adapter SHALL be implemented **only** for a carrier whose verdict is `ADAPTER`. Terms of use are
   **not verified** by this spec; a carrier whose terms forbid automated access SHALL get `MANUAL_ONLY`.
3. Every shipped adapter SHALL ship with parser unit tests over **recorded fixtures** (sample payloads
   stored in `src/test/resources`), and a contract test of the SPI that every adapter passes. Tests
   SHALL NOT call the real carriers.
4. An optional aggregator adapter (17TRACK, AfterShip) or a token API adapter (InPost) is **not built**
   here; the SPI SHALL be documented so it can be added.
5. Carriers with a `MANUAL_ONLY` verdict SHALL still be selectable (`carrierCode`), show the carrier's
   public tracking link when one can be built from the number, and accept manual data.

### Requirement 9: Tracking scheduler

**User Story:** As a manager, I want open shipments refreshed a few times a day, so that ETAs stay current
without anyone clicking.

#### Acceptance Criteria

1. `ShipmentTrackingScheduler` SHALL be driven by `foremen.procurement.tracking.cron` (default `-`,
   disabled; env `FOREMEN_PROCUREMENT_TRACKING_CRON`, with docker-compose mapping an empty value to `-`)
   and `foremen.procurement.tracking.zone` (default `Europe/Warsaw`). The recommended production value is
   `0 0 8,11,14,17 * * MON-FRI` (**4 times per working day**, weekdays, working hours).
2. Each run SHALL select shipments with `delivery_method = CARRIER`, a non-terminal status, an enabled
   adapter, and `next_check_at <= now`, ordered by `next_check_at`, limited to a batch size
   (default 200). Terminal shipments (`DELIVERED`, `CANCELLED`) SHALL never be selected.
3. After a successful fetch `next_check_at` SHALL be set to the next scheduled slot opportunity: now plus
   the minimum shipment interval, so a run per cron slot refreshes each open shipment about once per slot.
4. Selected shipments SHALL be processed sequentially, grouped by carrier, honouring Requirement 7.6
   (pause between calls, carrier cool-down). One failing shipment SHALL NOT stop the batch.
5. A run SHALL be a no-op when the master switch is off. There is no distributed lock (single node, as
   `ProcurementOcrSweepService`); the javadoc SHALL mention `@SchedulerLock` for a multi-node future.
6. A run SHALL log one summary line: selected, refreshed, unchanged, failed, skipped (paused carrier).

### Requirement 10: Manual refresh

**User Story:** As a buyer or manager, I want to refresh one shipment now, so that I do not wait for the
next slot.

#### Acceptance Criteria

1. `POST .../refresh` SHALL require `SHIPMENTS` UPDATE, a `CARRIER` shipment, a non-terminal status and an
   enabled adapter; otherwise `error.shipment.refresh.notAvailable`.
2. Refreshes are **rate-limited per shipment**: not more than one per 60 seconds (configurable)
   regardless of the user; a premature call SHALL answer `429` with `error.shipment.refresh.tooSoon` and
   `retryAfterSeconds` in the body.
3. A refresh SHALL bypass the minimum shipment interval but still respect a carrier cool-down
   (`error.shipment.refresh.carrierPaused`) and the per-adapter call spacing.
4. The response SHALL be the updated `ShipmentDto` (or the unchanged one with `lastCheckedAt` updated and
   a `refreshResult` of `UPDATED`, `UNCHANGED`, `FAILED`).
5. A refresh failure SHALL answer `200` with `refreshResult = FAILED` and the classified reason code; it
   SHALL NOT return `5xx` for a carrier problem.

### Requirement 11: ETA propagation to `plannedDeliveryDate`

**User Story:** As a foreman, I want the planned delivery date of a position to reflect the real shipment,
so that every screen shows the same date.

#### Acceptance Criteria

1. `PlannedDeliveryDateSynchronizer` SHALL gain `onShipmentChanged(positionId)` (called after a shipment
   is created, edited, cancelled, refreshed or delivered). It recomputes the position's
   `plannedDeliveryDate`: the **latest** `eta_date` among the position's Active_Shipments that have one;
   if there is none and the position has a `DELIVERED` shipment, the value is left unchanged; otherwise
   it falls back to the request's `expectedDeliveryDate` (or `null` without a request).
2. `onRequestDateChanged` SHALL **skip** positions that currently have an Active_Shipment with an ETA, so a
   request-date edit never overwrites a shipment ETA. Terminal positions keep their last value.
3. A change of the effective ETA to a **later** date SHALL publish `ShipmentEtaDelayedEvent`
   (shipment id, old, new, actor or null); a first ETA or an earlier ETA publishes nothing.
4. The ETA change SHALL be evaluated for Downtime_Risk (Requirement 14.4) in the same transaction
   boundary, publishing `ShipmentDowntimeRiskEvent` when it applies.
5. `plannedDeliveryDate` SHALL remain system-owned: no endpoint writes it directly; the synchronizer is
   its only writer, as before.

### Requirement 12: Extended `upcoming` endpoint

**User Story:** As a foreman, I want my upcoming deliveries with carrier, status and driver contact, so
that I can prepare the site and call the driver.

#### Acceptance Criteria

1. `UpcomingDeliveryDto` SHALL gain **additive, nullable** fields describing the nearest Active_Shipment
   of the position: `shipmentId`, `shipmentStatus`, `deliveryMethod`, `carrierCode`, `etaDate`,
   `etaTimeFrom`, `etaTimeTo`, `driverName`, `driverPhone`, `trackingPageUrl`, `shopOrderLink`,
   `shipmentCount` (Active_Shipments of the position) and `degraded`. Existing fields and their meaning
   SHALL NOT change (non-regression).
2. The "nearest" shipment SHALL be the Active_Shipment with the earliest ETA (ties by id); a position
   without an Active_Shipment returns the shipment fields as `null`.
3. The query SHALL stay a single projection with the shipment data fetched without N+1 (batch fetch of
   active shipments for the result set). Stages and the `days` window are unchanged; positions in
   `IN_DELIVERY` and `PARTIALLY_RECEIVED` already qualify.
4. The DTO SHALL contain **no money field** and no purchase-side field; a reflection guard test SHALL
   assert it (as FOR-11-02 does for client DTOs).
5. A new optional parameter `shipmentId` SHALL filter to the positions of one shipment (used by the
   notification deep link). Project scoping is unchanged.

### Requirement 13: Materials overlay endpoint (Gantt)

**User Story:** As a manager or foreman, I want to see on the project Gantt when materials must be
ordered and when they arrive relative to the works, so that I can prevent downtime.

#### Acceptance Criteria

1. `GET /api/purchase-positions/schedule-overlay?projectId=` (`PURCHASE_POSITIONS` READ, project access
   enforced) SHALL return, for the project's schedule: `projectId`, `anchorDate`, and `categories[]` with
   `workCategoryId`, `code`, `name`, `worksStartDate` (nullable) and `positions[]`; plus `unlinked[]` for
   positions without a resolvable category.
2. A position's category SHALL be resolved from its manual `workCategory`, else through the existing
   automatic resolution (`ScheduledCategoryService` / `WorkCategoryResolver`); the endpoint SHALL reuse
   them and add no new resolution rule.
3. Each position entry SHALL contain: `positionId`, a display label (material model and producer),
   `status`, `needByDate`, `orderByDate`, `arrivalDate` (= `plannedDeliveryDate`), `shipmentStatus`
   (nearest Active_Shipment, nullable) and the derived flags `orderByMissed`, `downtimeRisk`,
   `noArrivalDate`. **No money field**; cancelled positions are omitted.
4. `worksStartDate` SHALL be computed from the earliest bar start of the category:
   `anchor + ((startHalfDays + 1) / 2 - 1)` days, `null` when the category has no bars or the project has
   no start date (then `downtimeRisk` is `false` and the category carries `worksStartDate = null`).
5. `downtimeRisk` SHALL be `true` when `arrivalDate` is after `worksStartDate` and the position is not
   `RECEIVED`; `orderByMissed` SHALL be `true` when `orderByDate <= today` and the position is before
   `ORDERED` (and not `CLIENT_OWN`).
6. The endpoint SHALL be read-only, one bounded set of queries (no N+1), and SHALL return `200` with
   empty `categories` for a project without a schedule.
7. The same flag computation SHALL be implemented once (`MaterialScheduleRiskService`) and reused by the
   deadline sweep (Requirement 14) and the ETA events (Requirement 11).

### Requirement 14: Deadline sweep (order-by and downtime risk)

**User Story:** As a project manager, I want to be told when an order-by date has passed or when materials
will arrive after the works start, so that I can act before the crew is idle.

#### Acceptance Criteria

1. `ProcurementDeadlineSweepService` SHALL run on `foremen.procurement.deadline-sweep.cron` (default `-`,
   disabled; env `FOREMEN_PROCUREMENT_DEADLINE_SWEEP_CRON`; the recommended value is daily, e.g.
   `0 0 7 * * *`) in `Europe/Warsaw`.
2. **Order-by:** candidates are positions with `orderByDate <= today`, status in `PLANNED` ... `WITH_BUYER`,
   `sourcingPath <> CLIENT_OWN`, and `order_by_notified_for IS DISTINCT FROM orderByDate`. For each, the
   sweep SHALL stamp `order_by_notified_for = orderByDate` and publish `PositionOrderByMissedEvent`. The
   marker keeps the notification **one-shot per distinct order-by date** (a new date re-arms it with no
   reset code).
3. Positions of `CANCELLED` and `RECEIVED` status SHALL never be candidates.
4. **Downtime risk:** the sweep SHALL also evaluate every Active_Shipment of projects with a schedule and,
   when Downtime_Risk holds and `downtime_notified_for IS DISTINCT FROM eta_date`, stamp
   `downtime_notified_for = eta_date` and publish `ShipmentDowntimeRiskEvent`. This covers works that
   move earlier after the shipment was created.
5. The sweep SHALL be idempotent: running it twice in a row emits nothing the second time.
6. A sweep failure for one position or shipment SHALL be logged and SHALL NOT stop the run.

### Requirement 15: Notifications (emit-only)

**User Story:** As a project member, I want to be told about the events that need my attention.

#### Acceptance Criteria

1. A `ShipmentNotificationEmitter` (pattern of `ProformaOrderNotificationEmitter`) SHALL emit the four
   types of the Notifications table through `NotificationService.create(userId, type, null, link)`.
2. Listeners SHALL be `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)` for
   `ShipmentCreatedEvent`, `ShipmentEtaDelayedEvent`, `ShipmentDowntimeRiskEvent` and
   `PositionOrderByMissedEvent`.
3. Recipients SHALL be resolved from ACTIVE project members by project role code (`MANAGER`, `FOREMAN`),
   de-duplicated, with the actor removed; a recipient who is both a manager and a foreman is notified
   once, with the manager link.
4. A delayed event SHALL be emitted for ETA moves of at least one calendar day; a same-day change emits
   nothing.
5. The deep link SHALL target the page the recipient can open: the foreman variant
   `/deliveries/upcoming?shipment={id}`; the manager variant `/deliveries/shipments?shipment={id}`.
6. Resolution or emission failures SHALL be logged per recipient and SHALL NOT roll back or block the
   triggering operation.
7. Creating a shipment SHALL NOT notify the buyer; cancelling a shipment and a `DELIVERED` status emit no
   notification (visible in the lists).

### Requirement 16: API surface

**User Story:** As a frontend developer, I want a small, predictable shipment API.

#### Acceptance Criteria

1. The controller SHALL expose under `/api/shipments`: list (paged, filters `projectId`, `status`,
   `carrierCode`, `externalOrderId`, `positionId`, `etaFrom`, `etaTo`, `degraded`, text over tracking
   number and driver name), get by id, create, update, cancel, refresh and ETA revert.
2. `ShipmentDto` SHALL contain: id, projectId, project name, `externalOrderId` and its reference number,
   method fields, status, status source, ETA fields and source, driver fields, links, note, timestamps,
   `degraded`, `trackingNumberSuspect`, `trackingPageUrl`, `refreshAvailable`, `lines[]` (position id,
   material label, unit name, quantity, position status) and `events[]` (latest first, capped at 50).
3. The DTO SHALL contain **no money field**; unit and material names are localised through the existing
   locale resolver (PL default, RU on `ru`).
4. A `FOREMAN` (READ only) SHALL receive the DTO with `note` set to `null` (the buyer's internal note) and
   **with** driver name, driver phone, links and tracking number (the foreman needs them on site). The
   purchase-side fields do not exist in the DTO at all.
5. Validation errors SHALL use `error.shipment.*` keys with HTTP 400; unknown ids 404
   (`error.shipment.notFound`); stage conflicts 409.
6. The list SHALL be project-scoped for non-company-wide roles (the existing `ProjectScopedService`
   filtering) and sorted by ETA ascending (nulls last), then id.
7. Position detail screens SHALL be able to read the shipments of one position
   (`GET /api/shipments?positionId=`); no change to the position DTO is required.

### Requirement 17: Project scope and visibility

**User Story:** As an administrator, I want shipments to follow the same visibility rules as positions.

#### Acceptance Criteria

1. `ADMIN` sees all; `BUYER` and `FINANCIER` are company-wide (read); `MANAGER` and `FOREMAN` see only
   projects they are an ACTIVE member of; `CLIENT` and `WORKER` have no access (403).
2. A user without access to the project of a shipment SHALL get 404 for that shipment (no existence leak),
   consistently with the other procurement resources.
3. The overlay and `upcoming` endpoints SHALL apply the same project scope; a foreman never sees another
   project's deliveries or overlay.
4. Driver phone numbers SHALL be returned only to roles holding `SHIPMENTS` READ or `PURCHASE_POSITIONS`
   READ with project access (never to `CLIENT`).
5. No shipment endpoint SHALL be added to the client-safe surface of FOR-11-02.

### Requirement 18: Managed-entity checklist and datasets

**User Story:** As a developer, I want the new entity wired into the permission matrix, datasets and
dashboards as the repository standard requires.

#### Acceptance Criteria

1. The seed changeset, ADMIN grant, `@PermissionResource` and `getProjectIdPath()` of Requirement 1 SHALL
   be present (entity-creation checklist steps 1 to 4).
2. A `ShipmentsDataset` (`code SHIPMENTS`, `requiredResources {SHIPMENTS}`, project path `project.id`,
   date field `etaDate`) SHALL be registered with selectable / filterable / groupable fields: `status`,
   `deliveryMethod`, `carrierCode`, `etaDate`, `shippedAt`, `deliveredAt`, `degraded`; and aggregatable
   `id` (count). It SHALL **not** expose `trackingNumber`, `trackingUrl`, `shopOrderLink`, `driverName`,
   `driverPhone`, `note`, event notes or raw tracking text (`SensitiveFieldGuardTest` stays green).
3. `DatasetCoverageTest` SHALL pass with `SHIPMENTS` covered by the dataset (no exclusion entry).
4. Seeded library widgets for the dataset are **not required**; the dataset is usable from the admin
   widget builder.

### Requirement 19: Frontend routes, navigation and tabs

**User Story:** As a user, I want to reach shipments and upcoming deliveries from where I already work.

#### Acceptance Criteria

1. New routes SHALL be added next to `/deliveries/proformas` and `/deliveries/external-orders`:
   `/deliveries/shipments`, `/deliveries/shipments/:id` (`SHIPMENTS` READ) and `/deliveries/upcoming`
   (`PURCHASE_POSITIONS` READ), registered in `route-permissions.ts` and `router.tsx`.
2. `DeliveriesSectionNav` SHALL gain a **Shipments** tab (visible with `SHIPMENTS` READ); the foreman
   (who has no planner screen) reaches `/deliveries/upcoming` from the navigation.
3. `config/navigation.ts` SHALL get an item `upcomingDeliveries` (path `/deliveries/upcoming`, icon
   `truck`, `PURCHASE_POSITIONS` READ) shown to `FOREMAN`; the **FOREMAN bottom bar** SHALL add it as a
   fourth slot: `[ACTIVE_PROJECT, staffingGantt, addExpense, upcomingDeliveries]`. The other roles'
   profiles do not change; tests in `navigation.test.ts` and `BottomNav.test.tsx` are updated.
4. The existing `deliveries` nav item and its `alternatives` stay valid (a `BUYER` or `FINANCIER` with only
   `SHIPMENTS` READ reaches `/deliveries/shipments`).
5. The materials overlay is a **toggle inside the existing Gantt screens** (Requirement 22), not a new tab.

### Requirement 20: Frontend shipments screens (buyer and manager)

**User Story:** As a buyer, I want to register and follow shipments quickly; as a manager, I want an
overview of what is late.

#### Acceptance Criteria

1. The list SHALL show: project, order reference, carrier and tracking number (or method), status chip,
   ETA (date and window), last check time, a **degraded** badge, and quick actions (refresh, open tracking
   page). Filters SHALL cover project, status, carrier, "only delayed" (ETA before today and open) and
   free text. State SHALL be kept in the URL.
2. **Create dialog:** pick the project (prefilled from context), optionally the external order (offered
   from a paid order, with the "Add shipment" button on the external-order detail), choose positions with
   remaining quantity (the form shows shipped / cap per position) and enter the quantity, then the method
   fields. Quantity and stage errors from the API SHALL be shown at the field.
3. **Detail drawer:** all fields, the lines, the event history, the carrier link ("Open on carrier site"),
   **Refresh now** (disabled with a countdown after a refresh, `429` shown as a hint), **Edit manually**
   (ETA date, time window, driver name and phone, status), **Revert to carrier ETA**, **Cancel shipment**
   with a confirmation.
4. The UI SHALL state when the status is manual and when the carrier could not be read ("Tracking
   unavailable, enter data manually"), and SHALL never block manual entry.
5. Buttons SHALL appear only with the matching permission (`CREATE`, `UPDATE`); a `FOREMAN` sees the
   detail read-only.
6. The planner (FOR-11-01) SHALL show the nearest ETA in the existing delivery-date column without any
   layout change (it already shows `plannedDeliveryDate`).

### Requirement 21: Frontend foreman "upcoming deliveries" screen

**User Story:** As a foreman, I want a mobile list of what is arriving, when, and who to call.

#### Acceptance Criteria

1. `/deliveries/upcoming` SHALL be a **mobile-first list** over `GET .../upcoming` (default 14 days, a
   selector for 7 / 14 / 30 days, project filter prefilled with the active project), grouped by date with
   an "Overdue" group first.
2. Each card SHALL show: material and producer, quantity and unit, the project, the date and time window,
   the status chip, the carrier name or method, and a **phone button** (`tel:` link) when a driver phone
   exists, an **"Open tracking page"** link when `trackingPageUrl` exists, and the shop-order link when
   present. A `degraded` shipment SHALL show "Data entered manually / uncertain".
3. The `?shipment={id}` query parameter (notification deep link) SHALL scroll to and highlight the card.
4. An empty window SHALL show a clear empty state; loading and error states SHALL be handled (retry).
5. The screen SHALL be read-only (no receipt actions; FOR-11-05 adds them) and usable at 360 px width.
6. The screen SHALL show **no money**; there is none in the DTO.

### Requirement 22: Frontend Gantt materials overlay

**User Story:** As a manager, I want order-by markers on the Gantt; as a foreman, I want to see when
materials arrive against the works.

#### Acceptance Criteria

1. The project Gantt screens (the planning `ScheduleTab` and the read-only execution `PlanActualPanel`)
   SHALL get a **"Materials" toggle** (off by default, remembered per user) that loads the overlay
   endpoint and draws, per category row, markers and a thin lane under the bars; the existing bars,
   dragging and cascade behaviour SHALL be unchanged when the toggle is off.
2. Per row the overlay SHALL draw: an **order-by marker** (manager and buyer emphasis) and an
   **arrival marker** at `arrivalDate`; the **Works start** is the row's bar start. A position with
   `downtimeRisk` SHALL be drawn in the warning style with a tooltip "arrives N days after the works
   start"; `orderByMissed` SHALL be drawn in the error style; positions without an arrival date SHALL be
   listed in a compact "no date" chip. A row without bars shows no risk.
3. The overlay SHALL be read-only (no dragging of markers, no edits) and SHALL fail soft: an overlay error
   shows a small inline notice and leaves the Gantt usable.
4. For the `FOREMAN` the order-by markers are hidden by default (arrival vs works is the point); for
   `MANAGER`, `BUYER` and `ADMIN` both are shown. This is a UI default, not a permission.
5. A legend SHALL explain the markers; a11y: markers have text alternatives and the risk is not
   conveyed by colour alone.

### Requirement 23: Frontend i18n

**User Story:** As a Polish or Russian user, I want every new text in my language.

#### Acceptance Criteria

1. Every new string SHALL be a key in both `src/locales/pl.json` and `src/locales/ru.json` at parity
   (`i18n.keys.test.ts` enforces): `shipments.*` (list, form, detail, filters, statuses, methods, eta
   source, errors), `shipments.carrier.*` (`INPOST`, `DPD`, `DHL`, `GLS`, `UPS`, `FEDEX`,
   `POCZTA_POLSKA`, `ORLEN_PACZKA`, `OTHER`), `upcomingDeliveries.*`, `scheduleOverlay.*`,
   `nav.upcomingDeliveries`, `deliveries.nav.shipments`, and the four `notification.*` types of the
   Notifications table.
2. PL values SHALL be the Polish originals and RU values the Russian ones as in the table; the full list
   is in the design.
3. No user-facing text SHALL be hard-coded; dates and times use the existing formatting helpers.

### Requirement 24: Backend i18n and errors

**User Story:** As an API client, I want localised error messages.

#### Acceptance Criteria

1. Every new `error.shipment.*` key SHALL exist in `messages.properties` (PL) and `messages_ru.properties`
   (RU); a parity test SHALL fail when one side is missing.
2. Keys: `error.shipment.notFound`, `.terminal`, `.carrier.unknown`, `.trackingNumber.invalid`,
   `.link.invalid`, `.phone.invalid`, `.method.fieldsMissing`, `.position.notShippable`,
   `.line.exceedsCap`, `.line.exceedsOrderLine`, `.line.duplicate`, `.line.empty`, `.project.mismatch`,
   `.refresh.notAvailable`, `.refresh.tooSoon`, `.refresh.carrierPaused`, `.eta.revertNotAvailable`.
3. Messages SHALL not reveal other projects' data or carrier response bodies.

### Requirement 25: Verification and `test-cases.md`

**User Story:** As a reviewer, I want the behaviour proven by focused tests and a runnable scenario set.

#### Acceptance Criteria

1. Backend unit tests SHALL cover: delivery-method field rules, tracking-number normalisation, the
   shippable-cap and order-line quantity rules, the transition policy additions (new edge, no other
   change), the synchronizer precedence (shipment ETA vs request date), delay detection, back-off and
   cool-down arithmetic, the overlay flag computation including the half-day to date conversion, the
   notification recipient resolution, and the DTO reflection guard.
2. Property tests (jqwik) SHALL cover: Σ of active line quantities never exceeds the cap; the effective
   `plannedDeliveryDate` equals the latest active ETA (or the request date) for random shipment
   sequences; the order-by marker never re-notifies for an unchanged date; the back-off is monotonic and
   capped.
3. Adapter tests SHALL run only over recorded fixtures (Requirement 8.3). Integration tests (Testcontainers)
   SHALL cover create / update / cancel with real stage changes, the scheduler selection query, the
   `upcoming` extension and the overlay endpoint, following the test-execution rules (affected classes
   only, output to a temp log, JUnit XML as the source of truth).
4. Frontend tests SHALL cover: route permissions and navigation (including the FOREMAN bottom bar), the
   create form validation, the detail actions with refresh cooldown, the upcoming screen grouping and
   deep link, the overlay toggle and fail-soft behaviour, and locale parity of the new keys.
5. `test-cases.md` (Russian) SHALL be authored in this folder per the workspace standard: API tests
   against the dockerized app (Part A) and browser-engine scenarios (Part B), repeatable through a data
   generator and teardown, a regression section and the MD report template. Carrier calls in these
   scenarios SHALL use a **stub adapter** enabled by a test-only property, never a real carrier.
6. A test-only `StubTrackingAdapter` (`foremen.procurement.tracking.stub.enabled`, default `false`,
   code `STUB`) SHALL return scripted statuses by tracking-number prefix so scenarios are deterministic.

### Requirement 26: Non-regression

**User Story:** As a maintainer, I want the existing procurement behaviour unchanged.

#### Acceptance Criteria

1. The existing `upcoming` response fields, ordering, window validation and scoping SHALL be unchanged;
   only nullable fields are added.
2. The transition policy SHALL differ from today only by the edge `IN_DELIVERY -> SUPPLIER_PAID`
   (`AGREED`, `PACKAGE`); existing policy tests stay green.
3. A request `expectedDeliveryDate` change SHALL behave as before for positions without an Active_Shipment
   ETA.
4. The Gantt screens SHALL behave exactly as before with the Materials toggle off, and the FOR-05-10 /
   FOR-05-10b / FOR-06-02 tests stay green.
5. With `foremen.procurement.tracking.enabled=false` (the default) and both crons at `-`, the application
   SHALL start and run with no outbound call and no scheduled work from this spec.
6. FOR-11-03 external-order and proforma behaviour, the FOR-07-04 ledger and the FOR-06 construction
   purchase flow SHALL be untouched.
