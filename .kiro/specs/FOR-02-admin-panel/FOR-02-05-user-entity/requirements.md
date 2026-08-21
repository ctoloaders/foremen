# Requirements: User Entity (FOR-02-05-user-entity)

## Overview

Implement the User JPA entity with full CRUD stack. Users represent people who interact with the system. Each user has a role, locale preference for notifications, and display preferences (theme/font stored as JSONB).

## Functional Requirements

### 1. User Entity Fields

- 1.1 User SHALL have a `name` field (VARCHAR, required) — full display name
- 1.2 User SHALL have an `email` field (VARCHAR, unique, required)
- 1.3 User SHALL have a `phone` field (VARCHAR, nullable)
- 1.4 User SHALL have a `role_id` field (FK to roles, required) — determines user permissions
- 1.5 User SHALL have an `active` field (BOOLEAN, default true) — soft-delete mechanism
- 1.6 User SHALL have a `locale` field (VARCHAR(5), default 'ru') — language for notifications (values: 'ru', 'pl', 'en')
- 1.7 User SHALL have a `display_preferences` field (JSONB, nullable) — stores UI preferences: `{ colorScheme, fontSize }`

### 2. CRUD Operations

- 2.1 Admin SHALL be able to create users (POST /api/users)
- 2.2 Admin SHALL be able to update user fields (PUT /api/users/{id})
- 2.3 Admin SHALL be able to read users paginated (GET /api/users)
- 2.4 Admin SHALL be able to read a single user (GET /api/users/{id})
- 2.5 Admin SHALL be able to deactivate users (setting active=false), not hard-delete
- 2.6 Admin SHALL be able to view audit log for a user (GET /api/users/audit/{id})

### 3. Validation

- 3.1 `name` SHALL be non-blank
- 3.2 `email` SHALL be non-blank and valid email format
- 3.3 `email` SHALL be unique across all users (409 on duplicate)
- 3.4 `role_id` SHALL reference an existing role (400 if not found)
- 3.5 `locale` SHALL be one of: 'ru', 'pl', 'en'

### 4. ABAC Resource Registration

- 4.1 A new resource `USERS` SHALL be added to the resources table
- 4.2 ADMIN role SHALL have CRUD on USERS in the permissions matrix

### 5. JSONB display_preferences

- 5.1 display_preferences SHALL be stored as PostgreSQL JSONB column
- 5.2 display_preferences SHALL be mapped as Map<String, Object> in Java
- 5.3 A JPA AttributeConverter SHALL handle serialization/deserialization
- 5.4 Null display_preferences SHALL be allowed (user hasn't set preferences yet)

### 6. Audit

- 6.1 All CUD operations on User SHALL be audited via AuditLogDao
- 6.2 Audit records SHALL be retrievable via GET /api/users/audit/{id}

## Non-Functional Requirements

- 7.1 User entity SHALL extend BaseEntity (id, createdDate, createdBy, updatedDate, updatedBy)
- 7.2 Implementation SHALL follow the FOR-01 CRUD framework patterns (AdminDao, AdminService, AdminController)
- 7.3 Liquibase changeset SHALL create the users table and seed the USERS resource
- 7.4 No i18n fields on User entity (name, email, phone are personal data, not translatable)

---

*Created: August 2026*
