# Bug report: FOR-05-07-offer-approval

Screen URL: http://localhost:3000/projects/1/pricing

## Round 1 (2026-10-28)

- [x] 1. Offer price is 0 on the pricing tab even though the estimate has a non-zero work total — the sent offer carries a stale total and is not re-read live from the estimate
  - Scope (clarified with user): the offer sent to the client contains **only the cost of work**
    (labour) — materials are NOT part of the offer price. The labour total is already present on the
    estimate but absent on the offer.
  - Triage: observed — on `/projects/1/estimate` the estimate shows **Roboty razem 5 760,38**
    (a real, non-zero persisted labour total). On `/projects/1/pricing` the same project's offer
    (status **Wysłana** / SENT, revision 1, "Pakiet nie wybrany") shows **Netto 0,00 / VAT 0,00 /
    Brutto 0,00**. Expected — the offer price equals the estimate's work total (5 760,38 net, plus
    VAT/gross), independent of any package selection.
  - Verified in browser (2026-10-28): estimate header `Roboty razem 5 760,38`; pricing header
    `offer-total-net` / `offer-total-vat` / `offer-total-gross` all render `0,00` for the SENT
    offer.
  - Layer: **backend**. Root cause (traced): the offer total is NOT package-gated (confirmed — the
    offer with no package should still price). Offer totals are computed as `Σ estimate
    line.valueNet − discounts` by `OfferService.recomputeTotals`, where each line's `valueNet` is the
    persisted labour `unitPrice × quantity` (`EstimateRecomputeService.recomputeEstimate`). The defect
    is that `recomputeTotals` runs ONLY on write triggers — `prepareOffer`, `selectPackage`,
    `chooseFinishingConcrete`, and discount add/edit/remove — and stores the result on the offer
    (`offer.totalNet/totalVat/totalGross`). The READ path that the `/pricing` tab calls,
    `OfferService.getCurrentOfferByProject(projectId)` (`@Transactional(readOnly = true)`), simply
    loads the offer and the read-model assembler
    (`ClientOfferReadModelAssembler.toClientReadModel/toExecutorReadModel`) passes the STORED
    `offer.totalNet/VAT/gross` through verbatim — it never recomputes against the current estimate.
    So an offer prepared/sent while the estimate's persisted labour total was still 0 (before the
    Works×Rooms matrix was filled) keeps `totalNet = 0` forever, even after the estimate is later
    completed to 5 760,38. The displayed 0 is a STALE stored snapshot, not a live figure.
  - Spec basis (this is a spec violation, not ambiguous behavior): FOR-05-07 Requirement 19
    (Live_Referenced_Pricing) — the offer's prices are a **live reference** to the estimate's
    client-facing final prices, NEVER a stored copy; estimate-side changes (volumes, prices, applied
    package) MUST flow into the offer automatically through the `Offer.estimate` FK without any
    re-copy. R1.3 — `totalNet = estimate.totalNet − Σ Effective_Discount(net)`, computed OVER the
    estimate's live-referenced client-facing prices, and the offer "SHALL NOT persist its own copy of
    the per-line estimate prices." Serving the stored `offer.totalNet` on read breaks exactly this
    live-reference guarantee.
  - Fix direction: make the offer totals **live** on read. When the `/pricing` read resolves the
    current offer, recompute the totals from the live-referenced estimate (reuse the existing
    `recomputeTotals(offer)` logic) so the read model reflects the estimate's current work total —
    rather than returning the stored snapshot. Keep it package-independent (no package required).
    Options to settle at approval (see question): (a) recompute-on-read in the read path and surface
    the fresh totals without necessarily persisting them (purest live reference; the stored columns
    may be refreshed opportunistically), or (b) have the read path recompute-and-persist so the
    stored columns are brought current on every read. Materials are explicitly OUT of scope — the
    offer price stays labour-only — so the earlier range-vs-point materials question is dropped.
    Suspect:
    `foremen-backend/src/main/java/com/foremen/service/OfferService.java`
    (`getCurrentOfferByProject` read path + `recomputeTotals` reuse);
    `foremen-backend/src/main/java/com/foremen/service/offer/ClientOfferReadModelAssembler.java`
    (assembles the read model from the offer's stored totals — ensure it reflects the live recompute);
    the offer-by-project controller GET that serves `/pricing`.
  - Decision (user): Option 1 — recompute-on-read, no write on a GET.
  - Fix (Option 1, recompute-on-read): in `OfferService` extracted the pure `computeLiveTotals(offer)`
    (no mutation) shared by the write path (`recomputeTotals`, unchanged semantics) and a new read-path
    `applyLiveTotals(offer)` that refreshes the offer's in-memory `totalNet/VAT/gross` from the
    live-referenced estimate. Wired `applyLiveTotals` into the two `@Transactional(readOnly = true)`
    read methods `getOffer(offerId)` and `getCurrentOfferByProject(projectId)` (the latter on both the
    active-offer and latest-offer branches). Because the read transaction is `readOnly = true`
    (Hibernate `FlushMode.MANUAL`) and no `save`/`flush` is called, the refreshed totals are served to
    the caller without being persisted — a GET writes nothing; the stored columns are still brought
    current on the next genuine write path (prepare / select-package / discount). Package-independent;
    materials remain out of scope (offer stays labour-only).
  - Verified: `./gradlew compileJava` clean; affected tests pass — `OfferServicePrepareOfferPropertyTest`
    (3), `OfferTotalsCalculatorPropertyTest` (4), `OfferEntityAndReadModelStructuralTest` (5),
    `OfferServiceIntegrationTest` (12) → 24 tests, 0 failures/0 errors (JUnit XML).
  - _Requirements: 1.3, 19.1, 19.4_
  - _Depends on: none_

- [x] V1. Validate Round 1 in the browser
  - With project 1's estimate at `Roboty razem 5 760,38` and its SENT offer currently showing 0,00,
    reload `/projects/1/pricing` and confirm the offer Netto equals the estimate work total
    (5 760,38), with VAT/Brutto derived from the project VAT rate — no longer 0,00, and with no
    package selected. Mark `[x]` only on a confirmed browser pass.
  - Result (2026-10-28, PASS): after rebuilding the backend, `/projects/1/pricing` for the same SENT
    offer (revision 1, "Pakiet nie wybrany") now renders `offer-total-net` = **5 760,38** and
    `offer-total-gross` = **5 760,38**, exactly matching the estimate's `Roboty razem 5 760,38` — no
    longer 0,00, with no package selected and no re-prepare needed. `offer-total-vat` = 0,00 because
    this project's estimate has no VAT rate configured (`vatRateOf` → 0), so Brutto == Netto; that is
    correct and outside this bug's scope.
  - _Requirements: 1.3, 19.1, 19.4_
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 1

- **Wave 1:** 1 — make the offer totals live on read so the pricing tab reflects the estimate's
  current work total (backend).
- **Wave 2:** V1 — browser validation; depends on 1.

## Round 2 (2026-10-28)

- [x] 2. Withdrawn/closed offer shows a raw i18n key `offer.visibility.CLOSED` instead of a localized label
  - Triage: observed — when an offer is withdrawn (lifecycle `WITHDRAWN`/`REJECTED` → visibility
    `CLOSED`), the offer summary header renders the raw string `offer.visibility.CLOSED`. Expected —
    a localized label (PL/RU). Layer: **frontend (i18n)**. Root cause: `OfferSummaryHeader` renders
    `t(`offer.visibility.${offer.visibilityStatus}`)`, and `src/locales/pl.json` / `ru.json` define
    `offer.visibility.DRAFT` / `ON_APPROVAL` / `APPROVED` but are **missing** the `CLOSED` key — so
    i18next echoes the key. The backend `OfferVisibilityResolver` correctly maps terminal
    `WITHDRAWN`/`REJECTED` → `CLOSED`; only the translation is absent. Fix: add
    `offer.visibility.CLOSED` to both locales (PL + RU). Suspect:
    `foremen-frontend/src/locales/pl.json`, `foremen-frontend/src/locales/ru.json`
    (and `components/OfferSummaryHeader.tsx` as the consumer, no change needed).
  - Fix: added `offer.visibility.CLOSED` to both locales — PL "Zamknięta", RU "Закрыта".
  - Verified (browser, PL): a withdrawn offer's visibility badge renders "Zamknięta" (not the raw
    key). RU value present in `ru.json`; the render path is the same locale-agnostic `t()` call.
  - _Requirements: 17.4, 18.2_
  - _Depends on: none_

- [x] 3. A withdrawn/closed offer is a dead end — the executor can neither re-send it nor prepare a new offer; needs a Send-again / fresh-offer path. DECISION: **Option B** (prepare a NEW offer; keep WITHDRAWN terminal; frontend-only).
  - Triage: observed — after an offer is withdrawn, the pricing tab shows the terminal offer with a
    "terminal" hint and NO action buttons; there is no Send button (as there is for a fresh offer)
    and no way forward. Expected (user) — a cancelled offer should be sendable again, with a Send
    button like the first send. Layer: **both** (depends on chosen option). Root cause: (a) the
    frontend gates Send on `canExecutorWrite && offer.status === 'DRAFT'`, and a WITHDRAWN offer is
    terminal (`isTerminal` → `editable=false` → `canExecutorWrite=false`), so no Send/Withdraw
    button renders; (b) more fundamentally, the backend `OfferStatusMachine` treats `WITHDRAWN`
    (and `REJECTED`/`APPROVED`) as **terminal and immutable** — there is NO legal exit transition,
    and terminal-immutability is a spec invariant (R3.1/R3.8/R3.9, Property 5, enforced by
    `TerminalOfferImmutabilityPropertyTest`); and (c) the `/pricing` by-project read
    (`getCurrentOfferByProject`) returns the latest **terminal** offer when no active one exists, so
    the frontend's "prepare a new offer" empty-state (gated on `byProject.data == null`) never
    shows — leaving the executor stuck on a dead withdrawn offer with no prepare path either.
  - DECISION REQUIRED (lifecycle, not cosmetic) — three options; the fix direction depends on which
    you choose:
    - **Option A — re-send the SAME withdrawn offer (`WITHDRAWN → SENT`/`→ DRAFT`).** Add a new
      legal transition out of `WITHDRAWN` back to a sendable state and render Send again. This
      DIRECTLY BREAKS the terminal-immutability invariant (R3.9 / Property 5) and the property test;
      it is the literal reading of the request but is a spec/design change, not a bug fix.
    - **Option B — prepare a NEW offer after withdrawal (recommended, spec-compatible).** Keep
      WITHDRAWN terminal. When the project's only/active offer is terminal (WITHDRAWN/REJECTED),
      surface the existing **Prepare offer** action again (the backend already permits
      `prepareOffer` once no NON-terminal offer exists — `findByProjectIdAndStatusNotIn(... TERMINAL)`),
      so the executor starts a fresh offer (revision resets) that then shows the normal Send button.
      This is frontend-only: show the prepare action for an executor when the resolved offer is
      terminal (and/or treat a terminal by-project offer as "no active offer" for the prepare CTA).
    - **Option C — allow WITHDRAW to be undone only (`WITHDRAWN → DRAFT`) as an explicit "reopen".**
      A narrower lifecycle addition than A (reopen to DRAFT, not straight to SENT), still a
      deviation from the current terminal model; would need a new action + status-machine edge +
      tests.
  - Suspect (by option): backend `service/offer/OfferStatusMachine.java` + `OfferService` +
    `TerminalOfferImmutabilityPropertyTest` (A/C); frontend
    `features/project-offer/components/OfferTab.tsx` (all options — button gating / prepare CTA) and
    its tests (B is frontend-only).
  - Fix (Option B, frontend-only): in `OfferTab.tsx` the terminal-offer hint now also renders a
    "Przygotuj nową ofertę" (`offer.action.prepareNew`, added PL+RU) action for an executor, wired to
    the existing `handlePrepare` (`prepareOffer` → captures the new offer id via `setOfferId`). The
    WITHDRAWN offer stays terminal/immutable (no status-machine change, invariant preserved); the
    executor simply prepares a NEW DRAFT offer which then shows the normal Send flow.
  - Verified (browser): on the withdrawn offer the terminal hint shows the prepare-new button;
    clicking it produced a fresh DRAFT offer ("Robocza", net 5 760,38) with the normal Send +
    Withdraw buttons and no terminal hint; clicking Send transitioned it to SENT ("Wysłana" / "Do
    uzgodnienia"). Full re-send flow works end to end.
  - _Requirements: 1.1, 3.1, 3.2, 3.7_
  - _Depends on: none_

- [ ] V2. Validate Round 2 in the browser
  - Bug 2: withdraw an offer and confirm the visibility badge shows a localized CLOSED label (PL and
    RU), not the raw key. Bug 3: after withdrawal, confirm the chosen path works — (B) the executor
    can Prepare a new offer which then shows the normal Send button and sends. Mark `[x]` only on a
    confirmed browser pass.
  - _Requirements: 17.4, 18.2, 1.1, 3.2_
  - _Depends on: 2, 3_

- [x] 4. Remove the discount logic from the offer page
  - Request (user): remove discount logic from the offer page. Layer: **frontend** (UI removal);
    backend discount endpoints/services can remain unused unless removal is explicitly requested.
  - SCOPE CONFIRMED with user (pending): the discount footprint on `/pricing` is an interlinked
    cluster, not a single widget. It comprises:
    (a) **Applied-discount list** — the "Zastosowane rabaty" section rendering
        `OfferDiscountList` (`offer.discounts.title`), plus its executor remove affordance.
    (b) **Per-material discount-request affordance** — the `LINE`-scoped "request discount" button +
        justification input on every finishing row in `ClientMaterialSelection`
        (`onRequestDiscount`, `offer.action.requestDiscount*`), wired via `handleRequestDiscount` →
        `useOpenDiscountRequest`.
    (c) **Negotiation thread** — the `DISCOUNT_REQUEST` / `MANAGER_PROPOSAL` / `MANAGER_REJECT`
        rounds timeline (`NegotiationThread`) and the round actions (client accept/decline,
        `ManagerRoundResponder` propose/reject). Negotiation on this screen exists to drive
        discounts, so it is part of the discount logic — BUT it is a larger removal.
  - Removal plan (frontend-only, once scope confirmed): delete/stop-rendering the applied-discount
    section (a) and the per-row request affordance (b) from `OfferTab` + `ClientMaterialSelection`;
    drop the `onRequestDiscount` prop and `handleRequestDiscount`/`useOpenDiscountRequest` wiring;
    and — per the confirmed scope — also remove the negotiation-thread UI + round responders (c) if
    "discount logic" includes negotiation. Prune now-unused imports/i18n keys and update
    `OfferTab.test.tsx` / `ClientMaterialSelection` tests (remove the discount-affordance
    assertions). The offer stays: status/visibility/revision/package/totals + the finishing
    selection surface + lifecycle actions (prepare/send/withdraw/approve/reject). Backend discount
    services/endpoints are left in place (dead) unless their removal is requested.
  - DECISION (user): remove **a + b + c** — the applied-discount list, the per-material
    discount-request affordance, AND the whole negotiation thread + round actions. Frontend-only;
    backend discount/negotiation endpoints left in place (unused).
  - _Requirements: (removal — supersedes R2 discount UI, R4 negotiation UI, R8.2/R8.3, R12.1 on this screen)_
  - _Depends on: none_
  - Fix (frontend-only, a+b+c removed):
    - **(a)** removed the applied-discount section + `OfferDiscountList` from `OfferTab`; deleted
      `components/OfferDiscountList.tsx`.
    - **(b)** removed the per-material discount-request affordance from `ClientMaterialSelection`
      (the `Percent`/`Input` imports, the `requesting`/`justification` state, `handleSubmitRequest`,
      the request JSX, and the `onRequestDiscount` prop on both the row and the component) and the
      `handleRequestDiscount`/`useOpenDiscountRequest` wiring + prop pass-through in `OfferTab`.
    - **(c)** removed the negotiation-thread section from `OfferTab` (the `NegotiationThread` render,
      the client accept/decline round actions, the inline `ManagerRoundResponder`, the
      `openManagerProposals`/`openClientRequests` derivations, and the
      `useManagerPropose`/`useManagerRejectRound`/`useClientAcceptRound`/`useClientDeclineRound`
      mutations); deleted `components/NegotiationThread.tsx`.
    - Pruned now-unused imports; updated `OfferTab.test.tsx` (removed the NegotiationThread describe
      block, flipped the discount-affordance assertions to assert ABSENCE, dropped the unused
      `makeRound` helper + `NegotiationRoundView` import). Backend discount/negotiation endpoints
      left in place (unused). i18n keys `offer.discounts.*` / `offer.action.requestDiscount*` /
      negotiation keys left in the locale files (harmless, no longer referenced).
  - Verified: `npx tsc -b` clean; `npx vitest run src/features/project-offer` → 3 files / 38 tests
    pass. Browser: the pricing tab shows no applied-discount list, no per-row request-discount
    control, and no negotiation thread, while status/visibility/revision/package/totals + the
    finishing surface + lifecycle actions render intact.
  - _Requirements: (removal — see above)_
  - _Depends on: none_

- [x] V2. Validate Round 2 in the browser
  - Bug 2: withdraw an offer and confirm the visibility badge shows a localized CLOSED label (PL and
    RU), not the raw key. Bug 3 (Option B): after withdrawal, confirm the executor can Prepare a new
    offer which then shows the normal Send button and sends. Bug 4: confirm the discount UI
    (applied-discount list + per-material request affordance [+ negotiation thread per confirmed
    scope]) is gone from the pricing tab while the rest of the offer renders intact. Mark `[x]` only
    on a confirmed browser pass.
  - _Requirements: 17.4, 18.2, 1.1, 3.2_
  - _Depends on: 2, 3, 4_

## Parallel execution plan (waves) — Round 2

- **Wave 1 (parallel):** 2 (i18n CLOSED key, frontend locales), 3 (Option B prepare-path in
  OfferTab), 4 (remove discount UI) — 2 touches only locale JSON; 3 and 4 both touch `OfferTab.tsx`
  so they are NOT independent and must be **serialized** (see Wave note).
- **Wave note:** 3 and 4 both edit `features/project-offer/components/OfferTab.tsx`, so despite being
  logically independent they share a file and are applied sequentially (3 then 4) to avoid a
  conflicting edit; only 2 is truly parallel.
- **Wave 2:** V2 — browser validation; depends on 2, 3, 4.

## Round 3 (2026-10-29)

- [x] 5. Finishing-material search dropdown shows stale options after an offer-level package change
  - FIXED (2026-10-29): added `useEffect(() => setLinePackageCode(selectedPackageCode),
    [selectedPackageCode])` to `FinishingRow` in `ClientMaterialSelection.tsx` so the per-line
    package re-syncs when the offer-level `selectedPackageCode` prop changes; the picker's `filter`
    then changes and `AsyncEntitySelect` refetches. No change to `AsyncEntitySelect` /
    `use-reference-options` (query layer was already correct).
  - Triage: observed — on `/projects/1/pricing`, when choosing/changing a finishing material the
    product search dropdown is filtered by the selected package; after the user changes the offer's
    PACKAGE the dropdown still shows the OLD package's materials (it does not reload). Expected — the
    materials dropdown reloads (refetches with the new package filter) after a package change. Layer:
    **frontend**. Root cause (traced): the options query layer is CORRECT — `AsyncEntitySelect` →
    `useReferenceOptions` keys its `useInfiniteQuery` on the `filter` (`['reference-options', …,
    filter ?? '', …]`), so a changed filter refetches. The bug is in `ClientMaterialSelection.tsx`
    `FinishingRow`: `const [linePackageCode, setLinePackageCode] = useState(selectedPackageCode)` is
    seeded ONCE on mount and never re-syncs when the offer-level `selectedPackageCode` prop changes.
    So `finishingConcreteFilter(typeId, linePackageCode)` keeps the stale package code, the `filter`
    prop doesn't change, the query key doesn't change, and the picker shows the old package's
    materials. (Changing the PER-LINE package select works, because that updates `linePackageCode`
    directly.) Fix: re-sync the per-line package to the offer package when it changes —
    `useEffect(() => setLinePackageCode(selectedPackageCode), [selectedPackageCode])` in `FinishingRow`.
    Optionally also clear a now-invalid staged selection for the line when the effective package
    changes so the trigger doesn't show a product outside the new package.
  - Suspect: `foremen-frontend/src/features/project-offer/components/ClientMaterialSelection.tsx`
    (`FinishingRow` `linePackageCode`). No change needed in `AsyncEntitySelect` / `use-reference-options`.
  - _Requirements: FOR-05-07 R11 (finishing selection per package)_
  - _Depends on: none_

- [x] V3. Validate Round 3 in the browser
  - PASS (2026-10-29): on `/projects/1/pricing` opened a finishing line's picker (package "Budżet
    (START)"), changed the package to "Lux (PRESTIGE)" and reopened the picker — the picker fired
    `GET /api/finishing-materials?...&query=type.id==30 AND packages.code==lux` (HTTP 200), i.e. it
    refetched with the NEW package code instead of the stale `packages.code==budget`. This exercises
    the exact `linePackageCode → finishingConcreteFilter → AsyncEntitySelect refetch` chain the new
    `useEffect` feeds for the offer-level package change. Validated against the live Docker stack.
  - On `/projects/1/pricing`, open a finishing line's material picker, note the options; change the
    offer package; reopen the picker and confirm the options reloaded to the new package's materials.
    Mark `[x]` only on a confirmed browser pass.
  - _Requirements: as bug 5 (Round 3)_
  - _Depends on: 5_

## Parallel execution plan (waves) — Round 3

- **Wave 1:** 5 (Round 3) — single frontend effect in `ClientMaterialSelection`.
- **Wave 2:** V3 — browser validation; depends on 5.
