# Bug report: FOR-11-03-payments-proformas-external-orders

Screen URL: http://localhost:3000/deliveries/proformas, /projects/<id>/proformas

## Round 1 (2026-11-16)

Source: e2e run of `foremen-qa-auto/e2e/tests/for11/03-payments-proformas` (90 passed, 3 skipped as bugs,
3 environment skips: PDF storage token expired). Evidence: `tests/for11/03-payments-proformas/FINDINGS.md`.

Decision for BUG-2: R22.2 (menu/tab visibility) wins over the permission-driven menu; the FINANCIER keeps
the R1.2 READ grant on `CLIENT_PROFORMAS` (data access), but the staff proformas list is hidden by role.

- [x] 1. A CLIENT can open the staff list `/deliveries/proformas` and sees an error instead of the 403 page
  - Triage: observed: page "Wystąpił błąd" (the API correctly answers 403); expected: redirect to `/403`, like `/deliveries/external-orders`. Layer: frontend. Suspect: `foremen-frontend/src/config/route-permissions.ts` (route gated only by `CLIENT_PROFORMAS` READ, which CLIENT holds for its own endpoints).
  - _Requirements: R22.2, R22.4_
  - _Depends on: none_
- [x] 2. The FINANCIER sees "Proformy" in the menu and can open `/deliveries/proformas`
  - Triage: observed: menu lists "Proformy"; expected: FINANCIER sees only the external-orders entry. Layer: frontend. Suspect: `config/navigation.ts`, `route-permissions.ts`.
  - _Requirements: R22.2_
  - _Depends on: 1_ (same file)
- [x] 3. The manager's "Proforma opłacona" notification does not show the proforma
  - Triage: observed: the deep link opens the project proformas tab without selecting/highlighting the proforma; expected: the proforma is shown/highlighted. Layer: frontend. Suspect: `features/client-proformas/components/ClientProformasPanel.tsx` (staff branch ignores `?proforma`).
  - _Requirements: R22.3_
  - _Depends on: none_
- [x] V1. Validate Round 1
  - Rebuild, remove `.skip` from the BUG tests, run `npx e2e run tests/for11/03-payments-proformas`.
  - _Depends on: 1, 2, 3_

## Parallel execution plan (waves) — Round 1

- **Wave 1 (parallel):** 1, 3 — different files.
- **Wave 2:** 2 — depends on 1 (`route-permissions.ts`).
- **Wave 3:** V1

## Round 1 — fixes and validation results (2026-11-16)

| Bug | Fix | Files |
|-----|-----|-------|
| 1 | Route rules support excluded roles; `/deliveries/proformas[/:id]` exclude CLIENT and FINANCIER, so they get `/403`. | `config/route-permissions.ts` (`isRouteGranted`), `app/guards/PermissionGuard.tsx`, `features/client-proformas/lib/staffRoles.ts` |
| 2 | The "Proformy" section tab is hidden for CLIENT and FINANCIER (FINANCIER keeps the R1.2 grant). | `app/pages/DeliveriesSectionNav.tsx` |
| 3 | The staff branch of the project proformas panel reads `?proforma` and shows that proforma's card. | `features/client-proformas/components/ClientProformasPanel.tsx` |

Tests: vitest `route-granted.for11.test.ts`, `deliveries-routes.for1103.test`.
Validation: former BUG tests of `tests/for11/03-payments-proformas` unskipped and pass; TC-B6-01 now checks the
proforma screens as MANAGER and the order screens as FINANCIER (R22.2). Full `tests/for10 tests/for11` run green.
Open (not a bug of this round): dashboard links to `/deliveries/proformas` are not role-filtered by `link-resolver.ts`.
