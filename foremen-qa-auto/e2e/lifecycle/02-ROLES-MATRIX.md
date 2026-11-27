# Roles and lifecycle involvement

Source: live ABAC matrix read from `role_resources` / `role_resource_operations` in the running
Postgres (`foremen-postgres-1`). Only resources that the lifecycle touches are listed. Operations:
C = CREATE, R = READ, U = UPDATE, D = DELETE, A = APPROVE. `—` means no grant.

Project membership also gates rows. A non-ADMIN user sees a project only when a `project_members`
row exists. Company roles are the matrix roles below. A project role (`projectRoleId`) labels the
person on the team but grants nothing by itself (FOR-03-04).

## Matrix for lifecycle resources

| Resource | ADMIN | MANAGER | ESTIMATOR | SALESMAN | FOREMAN | WORKER | FINANCIER | BUYER | CLIENT | OTHER |
|---|---|---|---|---|---|---|---|---|---|---|
| PROJECTS | CRUD | CRU | R | R | R | R | R | R | R | — |
| PROJECT_MEMBERS | CRUD | CRUD | R | R | R | R | R | — | — | — |
| ROOMS | CRUD | CRUD | RU | R | RU | R | R | — | R | — |
| ESTIMATE | CRUD | CRU | CRU | R | R | R | R | — | — | — |
| OFFERS | CRUD+A | CRUD+A | CRU | CRU | — | — | R | — | RA | — |
| SIGNABLE_DOCUMENTS | CRUD | CRUD | — | CRU | CRU | R | R | — | RU | — |
| SIGNABLE_DOCUMENT_TYPES | CRUD | — | — | — | — | — | — | — | — | — |
| WORK_SCHEDULE | CRUD | CRUD | RU | R | RU | R | R | — | R | — |
| WORK_REPORTS | CRUD | RDU | R | R | CRUD | CRUD | R | — | — | — |
| AMENDMENTS | CRUD | CRUD | — | R | R | — | CRUD | — | — | — |
| CLIENT_PAYMENTS | CRUD | CRUD | — | — | R | R | CRUD | — | R | — |
| CLIENT_MESSAGES | CRUD | CRU | R | R | R | — | R | — | CR | — |
| PAYROLL | CRUD | CRUD | — | R | — | — | CRUD | — | — | — |
| PURCHASE_REQUESTS | CRUD | CRUD | — | — | — | — | — | RU | — | — |
| PURCHASE_POSITIONS | CRUD | CRUD | — | — | R | — | R | CRUD | — | — |
| POSITION_APPROVALS | CRUD+A | CRUD+A | — | — | R | — | R | R | RA | — |
| EXTERNAL_ORDERS | CRUD+A | R | — | — | — | — | RA | CRUD | — | — |
| SHIPMENTS | CRUD | RU | — | — | R | — | — | CRUD | — | — |
| GOODS_RECEIPTS | CRUD | CRU | — | — | CRU | CR | R | R | — | — |
| USERS | CRUD | — | R | — | R | — | — | — | — | R |
| AUDIT | R | — | — | — | — | — | R | — | — | R |
| ROLES | CRUD | — | — | — | — | — | — | — | — | — |

Two grants do not match the spec or the guard. Both are in the bug report as observations or bugs:
- SALESMAN has SIGNABLE_DOCUMENTS CREATE in the matrix, but `SigningAuthorizationGuard` denies every
  document write to anyone except MANAGER, ADMIN and FOREMAN (for their owned types). The guard
  decides, so SALESMAN gets 403 (observation O-1).
- MANAGER has no SIGNABLE_DOCUMENT_TYPES READ, yet the guard lets MANAGER create contracts. MANAGER
  needs the type list to create a document, so the list call returns 403 (BUG-03).

## Lifecycle involvement per role

| Role | Stage 0 Foundation | Stage 1 Design | Stage 2 Execution | Stage 3 Delivery | Stage 4 Finalization | Notes |
|---|---|---|---|---|---|---|
| ADMIN | creates users, project | bypasses ABAC; forces activation | oversees, audit | oversees | can set COMPLETED | Only role that may delete a project and manage roles. |
| MANAGER | — | runs the project: rooms, offer send, team, schedule, contract request, readiness, activation | payments schedule, amendments, client replies, plan/fact | purchase requests | edits status | Main project owner. Cannot read document types (BUG-03). |
| ESTIMATOR | — | estimate lines, room quantities, offer prepare and send | — | — | — | Reads rooms, cannot create them. |
| SALESMAN | — | prepares offers, reads estimate | — | — | — | Cannot create contracts (guard). |
| FOREMAN | — | read-only estimate and schedule | approves work reports, goods receipt | receives goods | — | Owns protocol document types. |
| WORKER | — | assigned to a work category | submits work reports | — | — | Cannot approve. Cannot see payroll. |
| FINANCIER | — | read-only offers | records client payments, payouts, external orders | pays suppliers | — | Cannot approve offers. |
| BUYER | — | — | — | purchase positions, external orders, shipments | — | Not a project member role in the design stage. |
| CLIENT | — | approves offer, signs contract | signs amendments, posts questions, sees payments | approves positions | — | Must never see cost or margin. Password login should be refused (BUG-07). |
| OTHER | — | — | — | — | — | Must see nothing. Used for negative checks. |
