# Additional tasks: FOR-05-07-offer-approval (cancel / re-version offer on estimate change)

Screen URL: http://localhost:3000/projects/{id}/pricing

> **Status (2026-10-29):** NOT started. This spec's reaction depends entirely on FOR-05-05's
> `EstimateChangedEvent` (FOR-05-05 task 6), which is not yet implemented. Staged for a dedicated
> session after the FOR-05-05 recalc engine lands.

Enhancement batch (2026-10-29). Approved by the user. NEW feature tasks (not bug fixes). This spec
owns the offer domain (`OfferService`, `OfferStatusMachine`, `OfferEntity`, the generic
`NotificationService`/`OfferNotificationEmitter`). It consumes the `EstimateChangedEvent` published
by FOR-05-05.

## Scope (user intent)

When the estimate (or room dimensions driving it) changes such that the estimate numbers change:

1. **Non-approved active offer → withdraw it.** If the project has a non-terminal offer that is NOT
   yet approved (`DRAFT`/`SENT`/`CHANGES_REQUESTED`/`COUNTERED`), automatically withdraw it
   (→ `WITHDRAWN`) — user decision 3a.
2. **Approved offer → issue a new version.** If the project's latest offer was `APPROVED` (terminal),
   create a NEW `DRAFT` offer version (`revision = previous.revision + 1`) linked to the same project
   and estimate — user decision 3a.
3. **Actor / permission model.** The acting user is whoever changed the dimensions/estimate. The
   offer change happens **regardless of that user's offer role**, provided their initial estimate/
   room operation was permitted. The automatic offer transition therefore BYPASSES the
   `OfferStatusMachine` executor-role gate (it is a system-initiated side effect). An **audit record**
   of who triggered the offer change (acting user id + the trigger) MUST be stored — user decision 3b.
4. **Warning before it happens.** The user is shown a warning that approving the estimate change will
   withdraw / re-version the offer — user decision 3c. This is surfaced as part of the FOR-05-05
   change-impact approval dialog (the impact payload includes an "offer will be withdrawn / a new
   offer version will be prepared" line); the offer reaction then runs only on the force/approved
   path.
5. **Notification (the ONLY notification in this batch).** On an automatic offer withdraw or
   new-version, emit an in-app notification via the generic `NotificationService`.

### Design anchors (grounded in code)

- "Active offer" = the single non-terminal offer: `offerDao.findByProjectIdAndStatusNotIn(projectId,
  TERMINAL_STATUSES)` where `TERMINAL_STATUSES = {APPROVED, REJECTED, WITHDRAWN}`.
- Withdraw today: `OfferService.withdraw(offerId)` → `transitionStatus(offer, WITHDRAW)` through the
  `OfferStatusMachine`, which requires an executor actor role. A new **system** withdraw path must
  set `WITHDRAWN` without the executor-role gate (still only from a non-terminal state).
- New version: there is NO existing "create next offer row" method. `prepareOffer` creates a
  `DRAFT, revision=1` and refuses while a non-terminal offer exists — but after an APPROVED offer
  (terminal) that guard passes. Add a `reviseApprovedOffer(projectId)` that creates a new `DRAFT`
  `OfferEntity` with `revision = latestApproved.revision + 1`, same project/estimate, package seeded
  from the estimate's applied package (mirror `prepareOffer`'s seeding + `recomputeTotals`).
- Reaction trigger: a `@TransactionalEventListener(phase = AFTER_COMMIT)` on `EstimateChangedEvent`
  (published by FOR-05-05 task 6), so the offer reaction never rolls back the estimate write (mirror
  `OfferNotificationEmitter` / `ProcurementOcrNotificationEmitter` best-effort convention).
- Notification: `NotificationService.create(recipientUserId, type, body, deepLink)` with a new
  `OfferReactionNotificationEmitter` (mirror `OfferNotificationEmitter`).

### Notifications (in scope — the only ones this batch)

- **Type `offer.withdrawn.estimateChanged`** — WHO: the project's CLIENT member and the project's
  MANAGER member(s) (and the SALESMAN member if present). WHAT: an active, non-approved offer was
  auto-withdrawn because the estimate changed. Deep-link: `/projects/{projectId}/pricing`.
- **Type `offer.newVersion.estimateChanged`** — WHO: same recipients. WHAT: the approved offer was
  superseded by a new DRAFT offer version because the estimate changed. Deep-link:
  `/projects/{projectId}/pricing`.
- Emitted ONLY via the generic in-app `NotificationService` (no bespoke delivery; email/SMS/Telegram
  are future work). Message bodies localized PL + RU. New i18n notification TYPE keys added as above.

### i18n

- Frontend: the warning line in the FOR-05-05 approval dialog — `estimate.recalc.offerWithdrawWarning`
  and `estimate.recalc.offerReviseWarning` (PL + RU). Notification type labels/bodies localized
  server-side (PL + RU) following the existing offer-notification message convention.
- Backend: no new entity i18n fields.

## Tasks

- [ ] 1. Backend: system withdraw path (no executor-role gate)
  - Add `OfferService.withdrawSystem(offerId, actingUserId)` (or a `system` flag on `withdraw`) that
    transitions a non-terminal offer → `WITHDRAWN` WITHOUT the `OfferStatusMachine` executor-role
    check (still rejecting a terminal source), records an audit row (acting user + trigger
    "ESTIMATE_CHANGED") via the existing `AuditLogDao`, and flushes.
  - _Requirements: FOR-05-07 R3.7; user decision 3a/3b_
  - _Depends on: none_

- [ ] 2. Backend: revise-approved-offer (new version)
  - Add `OfferService.reviseApprovedOffer(projectId, actingUserId)`: resolve the latest `APPROVED`
    offer, create a new `DRAFT` `OfferEntity` with `revision = latestApproved.revision + 1`, same
    project + estimate, package seeded from the estimate's applied package, `recomputeTotals`,
    persist, and record an audit row (acting user + trigger "ESTIMATE_CHANGED"). Reuses
    `prepareOffer`'s seeding logic.
  - _Requirements: FOR-05-07 R1/R7; user decision 3a/3b_
  - _Depends on: none_

- [ ] 3. Backend: estimate-changed reaction listener
  - Add `OfferEstimateReactionListener` with `@TransactionalEventListener(phase = AFTER_COMMIT)` on
    `EstimateChangedEvent` (FOR-05-05 task 6). It resolves the project's offer state: if a non-terminal,
    non-approved active offer exists → `withdrawSystem`; else if the latest offer is `APPROVED` →
    `reviseApprovedOffer`; else no-op. Best-effort (log-and-swallow; never roll back the estimate
    change). Passes the acting user id carried on the event.
  - _Requirements: user decision 3a/3b_
  - _Depends on: 1, 2_

- [ ] 4. Backend: offer-reaction notification emitter
  - Add `OfferReactionNotificationEmitter` (mirror `OfferNotificationEmitter`): on a system withdraw
    emit type `offer.withdrawn.estimateChanged`; on a new version emit type
    `offer.newVersion.estimateChanged`. Recipients: the project's CLIENT + MANAGER(s) (+ SALESMAN if
    present) resolved via `ProjectMemberDao`. Deep-link `/projects/{projectId}/pricing`. Via
    `NotificationService.create`, best-effort. Triggered from task 3's reaction (either inline or via
    a second event).
  - _Requirements: notifications in scope above_
  - _Depends on: 3_

- [ ] 5. Frontend: offer-impact warning line in the approval dialog
  - Extend the FOR-05-05 change-impact dialog (task 7 there) to show, when the impact payload
    indicates an active/approved offer, a line: "the active offer will be withdrawn" or "a new offer
    version will be prepared". Localized `estimate.recalc.offerWithdrawWarning` /
    `estimate.recalc.offerReviseWarning`. The backend dry-run impact payload (FOR-05-05 task 2) must
    include an offer-reaction hint so the dialog knows which line to show.
  - _Requirements: user decision 3c_
  - _Depends on: 4_

- [ ] 6. i18n keys (PL + RU)
  - Frontend `estimate.recalc.offerWithdrawWarning` / `estimate.recalc.offerReviseWarning`; backend
    notification type bodies for `offer.withdrawn.estimateChanged` / `offer.newVersion.estimateChanged`
    at PL + RU parity.
  - _Requirements: all above_
  - _Depends on: none_

- [ ] 7. Author test-cases.md
  - Create/append `test-cases.md` per `.kiro/steering/test-cases.md`. API scenarios (Dockerized app):
    estimate change with a SENT offer → offer becomes WITHDRAWN + a notification row exists for the
    client/manager; estimate change with an APPROVED offer → a new DRAFT offer revision=N+1 exists +
    notification; audit row records the acting user + trigger; the transition bypasses the
    executor-role gate (triggered by a non-offer-role estimate editor). UI scenario: the approval
    dialog shows the offer-withdraw / revise warning line. Repeatability via generated draft project
    + run-id + teardown; regression group for the normal offer send/approve/withdraw lifecycle.
  - _Requirements: the requirements covered (FOR-05-07 R3.7/R1/R7, decisions 3a–3c, notifications)_
  - _Depends on: 1, 2, 3, 4, 5, 6_

- [ ] V. Validate end-to-end in the browser + API
  - Rebuild backend + frontend. On a draft project with a SENT offer: change the estimate/room,
    approve the warning → offer WITHDRAWN + bell notification. On a project with an APPROVED offer:
    change the estimate → new DRAFT version N+1 + notification. Confirm the audit record.
  - _Requirements: as all tasks_
  - _Depends on: 1, 2, 3, 4, 5, 6_

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1 (system withdraw), 2 (revise-approved), 6 (i18n keys) — independent.
- **Wave 2:** 3 (reaction listener) — depends on 1, 2.
- **Wave 3:** 4 (notification emitter) — depends on 3.
- **Wave 4:** 5 (dialog warning line) — depends on 4 (and the FOR-05-05 dialog).
- **Wave 5:** 7 — test-cases.md depends on all implementation.
- **Wave 6:** V — validation depends on all implementation.

> Cross-spec dependency: tasks 3–5 depend on FOR-05-05 task 6 (`EstimateChangedEvent`) and task 2
> (dry-run impact payload carrying the offer-reaction hint). Schedule this spec's Wave 2+ after
> FOR-05-05's event/impact tasks land.
