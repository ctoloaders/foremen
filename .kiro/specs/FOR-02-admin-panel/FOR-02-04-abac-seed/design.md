# Design Document: ABAC Seed (FOR-02-04-abac-seed)

## Overview

This design adds the CLIENT role and seeds the initial permissions matrix. The key insight: at this stage, the only resources that exist as actual entities are the ABAC meta-resources themselves (Role, Operation, Resource). Domain resources like projects, rooms, estimates will be added to the matrix incrementally as their entities are built.

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Only seed ROLES, OPERATIONS, RESOURCES | These are the only entities that exist right now. Domain resources will be added as they're implemented. Avoids forward-declaring resources that don't exist yet. |
| CLIENT role in a separate changeset (006) | Changeset 004-seed-roles already applied in existing DBs. New changeset maintains migration compatibility. |
| Only ADMIN gets access | ABAC management is admin-only. Other roles don't see these menu items at all. |
| ADMIN gets CRUD on ROLES, READ on OPERATIONS/RESOURCES | Admin can fully manage roles (create, edit, delete non-system). Operations and Resources are reference catalogs — read-only even for admin. |
| SQL-based inserts with subselects in 007 | role_resources references IDs from roles/resources/operations. Subselects avoid hardcoding auto-generated IDs. |

## Architecture

### Changeset Flow

```
004-seed-roles (existing: ADMIN, MANAGER, FOREMAN, WORKER, FINANCIER)
    ↓
005-create-role-resources (existing: schema only)
    ↓
006-seed-client-role (new: CLIENT)
    ↓
007-seed-permissions (new: ADMIN-only matrix)
```

### Permissions Matrix Detail

```
ADMIN:
  ROLES      → CREATE, READ, UPDATE, DELETE
  OPERATIONS → READ
  RESOURCES  → READ

MANAGER:    (no access)
FOREMAN:    (no access)
WORKER:     (no access)
FINANCIER:  (no access)
CLIENT:     (no access)
```

### Future Extension Pattern

When a new domain entity is added (e.g. FOR-06 adds PROJECTS):
1. Add new resource to `resources` table via a new changeset
2. Add role_resources + role_resource_operations entries for relevant roles
3. The permission matrix grows incrementally without touching existing data

### SQL Strategy for 007-seed-permissions

Uses `<sql>` blocks with INSERT...SELECT to resolve IDs dynamically:

```sql
-- Create role_resource entries (ADMIN × all 3 resources)
INSERT INTO role_resources (role_id, resource_id, created_date, created_by)
SELECT r.id, res.id, NOW(), 'system'
FROM roles r, resources res
WHERE r.code = 'ADMIN';

-- CRUD on ROLES
INSERT INTO role_resource_operations (role_resource_id, operation_id)
SELECT rr.id, o.id
FROM role_resources rr
JOIN roles r ON rr.role_id = r.id
JOIN resources res ON rr.resource_id = res.id
JOIN operations o ON o.code IN ('CREATE', 'READ', 'UPDATE', 'DELETE')
WHERE r.code = 'ADMIN' AND res.code = 'ROLES';

-- READ on OPERATIONS, RESOURCES
INSERT INTO role_resource_operations (role_resource_id, operation_id)
SELECT rr.id, o.id
FROM role_resources rr
JOIN roles r ON rr.role_id = r.id
JOIN resources res ON rr.resource_id = res.id
JOIN operations o ON o.code = 'READ'
WHERE r.code = 'ADMIN' AND res.code IN ('OPERATIONS', 'RESOURCES');
```

## Components

### New Files

| File | Purpose |
|------|---------|
| `database_files/changesets/006-seed-client-role.xml` | Adds CLIENT role to roles table |
| `database_files/changesets/007-seed-permissions.xml` | Seeds ADMIN-only permissions for ABAC meta-resources |

### Modified Files

| File | Change |
|------|--------|
| `database_files/changesets/002-create-resources.xml` | Seed data changed: ROLES, OPERATIONS, RESOURCES (instead of domain resources) |
| `database_files/changelog.xml` | Added includes for 006 and 007 |

---

*Created: August 2026*
