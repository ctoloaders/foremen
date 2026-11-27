# Lifecycle suite: bug report

Found in run-11. Status after the fix rounds. Step IDs refer to `03-ROLE-TIMELINES.md`. Run evidence is in
`lifecycle/reports/` (run-19 is the last full validation of the business flow; run-final-2 is the last
full validation after the Boot upgrade).

## Summary

| ID | Severity | Area | Status |
|---|---|---|---|
| BLK-1 | Blocker (environment) | Document storage (GCS) | Resolved: ADC re-authenticated, backend recreated |
| BUG-03 | High | Permissions: document types | Fixed |
| BUG-06 | High, security | Auth: deactivated user | Fixed |
| BUG-07 | High, security | Auth: client password | Fixed |
| BUG-10 | High | Amendments: create | Fixed |
| BUG-01 | Medium | Project status after offer | Fixed |
| BUG-02 | Medium | Estimate lock | Fixed |
| BUG-09 | Medium | Project delete | Fixed |
| BUG-11 | High | Backend availability: Java deadlock | Fixed: Spring Boot 4.0.10 (Spring Framework 7.0.11) |
| BUG-12 | Medium | Backend memory grows during long runs | Open: cause not confirmed (see section) |

## Fixes

### BLK-1: document storage
Google Cloud Storage refused the ADC refresh token. The ADC was re-authenticated and the backend container
recreated. Verified: contract `request-signatures` returns 200 and the PDF freezes. No code change.
One later failure was an SSL handshake to GCS, which passed on rerun (transient network).

### BUG-03: MANAGER and FOREMAN could not list document types
- **Cause:** the signing guard lets MANAGER create any document and FOREMAN its owned types, but neither role had
  READ on `SIGNABLE_DOCUMENT_TYPES`.
- **Fix:** changeset `database_files/changesets/269-grant-document-types-read.xml` (idempotent), registered in
  `changelog.xml`.
- **Check:** L17 PASS.

### BUG-06: deactivated user could sign in
- **Cause:** admin deactivation clears `active` but leaves `status` ACTIVE. Login checked only `status`.
- **Fix:** `AuthService.login` refuses `!user.isActive()` with 403 `error.auth.account.deactivated`.
  `AuthService.refresh` refuses the same.
- **Known limit:** access tokens already issued stay valid until they expire (30 minutes for employees).
- **Check:** L45 PASS.

### BUG-07: CLIENT could sign in with a password
- **Cause:** `AuthService.login` did not check the role. FOR-03-05 says clients never set a password.
- **Fix:** a CLIENT password login returns the same 401 `error.auth.invalid.credentials` as a wrong password.
- **Impact on existing e2e tests:** clients now sign in by one-time code. See "Test-side changes".
- **Check:** L46 PASS (401).

### BUG-10: POST /api/amendments did not open an amendment
- **Cause:** the inherited CRUD create inserted the entity directly, skipping `AmendmentService.create`, which sets
  `seqNo` and checks eligibility and the single-open rule. The insert failed on the NOT NULL `seq_no`.
- **Fix:** `AmendmentController` overrides `create` and calls `AmendmentService.create(projectId, note)`.
- **Check:** L36, L36b, L37, L38, L38b PASS.

### BUG-01: project did not move to OFFERED after the offer was sent
- **Cause:** nothing set `READY_TO_OFFER`. `OfferService.send` moved the project only from `READY_TO_OFFER`.
- **Fix:** the send moves the project DRAFT → READY_TO_OFFER → OFFERED (parent design §4.3, FOR-05-07 R3.2).
- **Check:** L11 PASS.

### BUG-02: estimate was never locked
- **Cause:** the estimate status never left DRAFT, so `DraftGateGuard` blocked nothing.
- **Spec:** FOR-05-07 R20.2 and TC-LOCK-01 lock the estimate once the project is ACTIVE. Edits stay allowed while the
  offer is out (the snapshot covers that). The earlier expectation (lock at send) was wrong and is corrected.
- **Fix:** `service/estimate/EstimateLockOnActivation` listens for `ProjectActivatedEvent` synchronously, inside the
  activation transaction, and sets the estimate to SIGNED. SIGNED is also the baseline amendments need.
- **Check:** L12 PASS (edit allowed before ACTIVE), L12b PASS (409 `error.estimate.locked` after ACTIVE).

### BUG-09: deleting a project with rooms returned 500
- **Cause:** foreign key `fk_rooms_project` violated. The Hibernate exception was not translated and became
  `error.internal`.
- **Fix:** `ProjectService.deleteById` catches the constraint violation and returns 409 `error.project.hasDependents`
  (message in PL and RU bundles).
- **Check:** L48 PASS.

### BUG-11: backend Java deadlock under concurrent requests (fixed)
- **Cause (confirmed by thread dump):** two request threads in Spring's `ConcurrentReferenceHashMap` wait on each
  other's locks, reached from `AnnotationTypeMappings$Cache.get`. This matches upstream spring-framework#35944
  (nested `computeIfAbsent` on the same map, fixed in Framework 7.0.2).
- **Fix:** `foremen-backend/build.gradle`: Spring Boot plugin 4.0.0 → 4.0.10, which pins Spring Framework 7.0.11.
- **Check:** compile succeeds. Affected backend test classes pass (AuthServiceLoginPropertyTest,
  RefreshTokenServicePropertyTest, ProjectServicePreservationTest, ProjectActivationServiceTest,
  ProjectActivationSignalPropertyTest, OfferServicePrepareOfferPropertyTest, DraftGateGuardPropertyTest,
  AmendmentServiceTest, AuthControllerIntegrationTest). Lifecycle run-final-2: 51 of 51 PASS.
- **Not proven under load:** the deadlock was seen once. Later runs show none, but they are not a load test.

## Open defect

### BUG-12: backend heap grows during long runs (open, cause not confirmed)

**Symptom.** During long mixed workloads (lifecycle suite plus the for11 06 folder), the live heap after a full GC
stays near the 2 GB ceiling, and one run hit `OutOfMemoryError`.

**Ruled out:**
- The PDF renderer. 300 renders of the same document keep the used heap flat (27 to 36 MB after GC).
- The application caches. Caffeine caches are bounded (`maximumSize` 500 or 1000). Static maps are keyed by class.
- Distinct SQL shapes. One lifecycle run logged 6,028 statements, normalised to 119 distinct shapes.

**Object counts** (`jdk.ObjectCountAfterGC`, one for11 06 run, 14 GCs). Live heap grew from about 16 MB to about
107 MB. The classes that grew most:
- `byte[]` +21 MB, `java.lang.reflect.Method` +11 MB (1k to 133k instances), `java.lang.String` +5.5 MB,
  `Object[]` +5 MB, `ConcurrentHashMap$Node` +5 MB, `HashMap$Node` +4 MB, `java.lang.Class` +4 MB (6k to 38k).
- `org.antlr.v4.runtime.atn.ATNConfig` (23k to 156k instances) is about 15 to 20 MB of that growth.

**Step 1 result (HQL parser, not confirmed as the cause).** Allocation samples on ATNConfig give these callers:
`StandardHqlTranslator.parseHql` (about 97 of 133 attributed samples) and Spring Data's
`DefaultEntityQuery` (36). The application's dynamic JPQL (approval and planner services) comes from fixed templates
with filter-dependent WHERE clauses. That gives a bounded set of strings. A grep found no inlined ID lists or values.
So the HQL parser is a contributor, but not the source of unbounded growth.

**Open question.** The growing reflection data (`Method`, `Class`) is not explained yet. Likely sources are dynamic
proxies or generated classes created per request or per distinct query. Not checked.

**Next steps:**
1. Count the distinct HQL strings handed to the parser (Hibernate query plan cache misses), to test whether the
   set is really bounded.
2. Find the source of the growing `Method` and `Class` objects, using allocation stacks from a longer run.
3. Check whether a newer Hibernate release changes the parser's memory behaviour (7.4.x is in the local Gradle cache).

**Stopgap:** raise the heap ceiling (container 7.7 GB; JVM default 25 percent) and restart the backend between long runs.

## Observations (not failing the suite)

- **O-1:** SALESMAN holds SIGNABLE_DOCUMENTS CREATE in the ABAC matrix, but `SigningAuthorizationGuard` denies it.
  L18 asserts the guard's 403. The matrix or the guard should be aligned.
- **O-2:** the client's view does not render the offer status label. The step reads the status through the API.
- **O-3:** `templateLocale: "pl"` (lowercase) is accepted on create, and generate then returns 404
  `error.document.template.not.found`. Only `PL` works. Create should reject it.
- **O-4:** the project edit form offers five statuses, not eight. READY_TO_OFFER, OFFERED and APPROVED cannot be
  selected.
- **O-5:** COMPLETED can be set with a plain PUT. No spec defines a gate (lifecycle gap G-1).
- **O-6:** the project list is paged and has no search, so a new project can be missing from the first page.
- **O-7:** ONLINE signing has no live provider (FOR-05-08 gap G-4). The suite signs with PRINT.

## Test-side changes

These are changes to the tests, not the product.

- **Clients sign in by one-time code.** `tests/support/client-otp.ts` requests the code, reads it from `otp_tokens`,
  and verifies it. `tokenOf` and `loginAs` route CLIENT users there. Seven for11/for10 UI sign-in helpers got the
  same branch.
- **L12 and L12b** split the estimate lock into the send and ACTIVE states (see BUG-02).
- **L22** reads the activation caused by the signed contract. The forced activation is gone.
- **L36b** adds a change line before the send. An empty amendment cannot be sent (422 `emptyNotSignable`, correct).
- **L38 and L38b** sign the amendment document as client and then executor (FOR-06-09 R1.4).
- **for11 `ui-payments` TC-B2-03** expected 403/404 for a foreign project. The product returns an empty page (membership
  scope), which does not leak rows. The assertion now checks `totalElements == 0`.
- **Lifecycle locale:** `LIFECYCLE_LOCALE=ru` runs the suite in Russian (`tests/lifecycle/support/i18n.ts`).

## Verification

- Backend unit and integration tests for the changed components: 91 of 93 passed before the Boot upgrade. The two
  failures predate this work: `AmendmentControllerAbacTest` (asserts changelog head 179, was 268 before this work) and
  `ProjectServiceProjectionTest` (NPE in `autoAddCreator`, test builds the service without `userDao`).
- Affected backend test classes on Boot 4.0.10: all pass.
- Lifecycle suite: run-19 (PL, before the Boot upgrade) 51 of 51; run-ru-01 (RU) 51 of 51; run-final-2 (PL, after the
  Boot upgrade) 51 of 51.
- for11 position-approval (api-access, ui-client): 23 PASS. for11 06 (api-client-payments, ui-payments): 37 PASS.
- Not re-run after the changes: the rest of `tests/for10` and `tests/for11`.
