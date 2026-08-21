# Implementation Plan: ABAC Seed (FOR-02-04-abac-seed)

## Overview

Add the CLIENT role and seed the initial ABAC permissions matrix. Only ADMIN has access to the 3 meta-resources (ROLES, OPERATIONS, RESOURCES). All other roles have no access.

## Tasks

- [x] 1. Update resource seed in 002-create-resources.xml
  - Replace PROJECTS/ROOMS/ESTIMATE/WAREHOUSE with ROLES, OPERATIONS, RESOURCES
  - Each with name_ru, name_pl, description_ru, description_pl
  - _Requirements: 2.1, 2.2_

- [x] 2. Create changeset 006-seed-client-role.xml
  - Add CLIENT role (code='CLIENT', name_ru='Клиент', name_pl='Klient', description_ru/pl, system=true)
  - Use precondition: sqlCheck that CLIENT role does not already exist
  - _Requirements: 1.1, 1.2, 1.3, 4.1, 4.2_

- [x] 3. Create changeset 007-seed-permissions.xml
  - Seed role_resources entries: ADMIN × (ROLES, OPERATIONS, RESOURCES)
  - Seed role_resource_operations: ADMIN+ROLES→CRUD, ADMIN+OPERATIONS→R, ADMIN+RESOURCES→R
  - Use SQL blocks with INSERT...SELECT for dynamic ID resolution
  - Use precondition: sqlCheck that role_resources is empty
  - Populate created_by='system', created_date=NOW()
  - _Requirements: 3.1–3.4, 4.1, 4.2, 5.1–5.4, 6.1, 6.2_

- [x] 4. Register changesets in changelog.xml
  - Add `<include>` for 006-seed-client-role.xml and 007-seed-permissions.xml
  - _Requirements: 7.1_

- [x] 5. Verify build compiles
  - _Requirements: 7.2_

## Permissions Matrix

| Role | ROLES | OPERATIONS | RESOURCES |
|------|-------|------------|-----------|
| ADMIN | CRUD | R | R |
| MANAGER | — | — | — |
| FOREMAN | — | — | — |
| WORKER | — | — | — |
| FINANCIER | — | — | — |
| CLIENT | — | — | — |

---

*Created: August 2026*
