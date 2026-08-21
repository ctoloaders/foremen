# Requirements: ABAC Seed (FOR-02-04-abac-seed)

## Overview

Populate the ABAC permission matrix via Liquibase: add CLIENT role, seed initial resources (ROLES, OPERATIONS, RESOURCES) representing the ABAC management module itself, and assign permissions only to ADMIN. Other resources (PROJECTS, ROOMS, etc.) will be added incrementally as their entities are implemented.

## Functional Requirements

### 1. CLIENT Role Seed

- 1.1 A new system role CLIENT SHALL be seeded with code='CLIENT', system=true
- 1.2 CLIENT role SHALL have name_ru='Клиент', name_pl='Klient'
- 1.3 CLIENT role SHALL have description_ru='Просмотр своих проектов и документов', description_pl='Przeglądanie swoich projektów i dokumentów'

### 2. Initial Resources

- 2.1 Resource catalog SHALL contain only: ROLES, OPERATIONS, RESOURCES
- 2.2 These represent the ABAC management module (meta-resources for managing access control itself)
- 2.3 Additional domain resources (PROJECTS, ROOMS, ESTIMATE, WAREHOUSE, etc.) SHALL be added in future specs as their entities are implemented

### 3. Initial Permissions Matrix

- 3.1 ADMIN role SHALL have CRUD (CREATE, READ, UPDATE, DELETE) on ROLES
- 3.2 ADMIN role SHALL have READ on OPERATIONS
- 3.3 ADMIN role SHALL have READ on RESOURCES
- 3.4 All other roles (MANAGER, FOREMAN, WORKER, FINANCIER, CLIENT) SHALL have NO access to ROLES, OPERATIONS, or RESOURCES
- 3.5 ABAC management resources are not visible in menus for non-ADMIN roles

### 4. Idempotency

- 4.1 The seed changesets SHALL use preconditions to prevent duplicate data insertion
- 4.2 Re-running the changesets on an already-seeded database SHALL not fail or create duplicates

### 5. Data Integrity

- 5.1 All role_resource entries SHALL reference existing roles
- 5.2 All role_resource entries SHALL reference existing resources
- 5.3 All role_resource_operations entries SHALL reference existing operations
- 5.4 The unique constraint (role_id, resource_id) on role_resources SHALL be respected

### 6. Auditability

- 6.1 The seed changeset SHALL populate created_by with 'system' for all role_resource rows
- 6.2 The seed changeset SHALL populate created_date with NOW() for all role_resource rows

## Non-Functional Requirements

- 7.1 The changesets SHALL be registered in changelog.xml after 005-create-role-resources.xml
- 7.2 The changesets SHALL use Liquibase best practices (preConditions, explicit column references)

## Permissions Matrix (Reference)

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
