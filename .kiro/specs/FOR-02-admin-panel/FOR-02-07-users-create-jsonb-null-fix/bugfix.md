# Bugfix Requirements Document

## Introduction

Creating a user through `POST /api/users` fails with an HTTP 500 error whenever the
request body does not include a `displayPreferences` value. Since the Users management UI
does not send `displayPreferences` on create, every user creation from the admin panel is
broken.

This is now understood as a **JDBC-type-resolution problem**, not merely a null-handling
problem. `UserEntity.displayPreferences` was mapped with
`@Convert(converter = JsonMapConverter.class)` whose relational (database-side) type is the
bare `java.lang.Object`. Because Hibernate has no resolvable JDBC type for that column, it
cannot bind a value for it — this affects BOTH a null bind and an empty-map (`{}`) bind.

An initial fix (mapper default to `Map.of()`) was shipped and FAILED: it only moved the error
from the null case (`null [Unknown Types value.]`) to the empty-map case
(`{} [Unsupported Types value: 545,108,554]`), confirming the column itself lacked a
resolvable JDBC type. The revised fix maps the `Map` field with `@JdbcTypeCode(SqlTypes.JSON)`
(the proven `AuditLogEntity` pattern), giving Hibernate a concrete JSON JDBC type so it binds
null as a typed `jsonb` null and non-null maps as `jsonb`. See design.md for the full rationale
and the behavior note (an absent value is stored as SQL `NULL`, and reads back as `{}` at the
API).

## Bug Analysis

### Reproduction

Request:

```
POST /api/users
Content-Type: application/json

{"name":"Alex","email":"cto@loaders.dev","phone":"+48789736624","roleId":2,"locale":"pl"}
```

Backend error:

```
org.springframework.dao.InvalidDataAccessResourceUsageException:
  Unable to bind parameter #4 - null [Unknown Types value.] [n/a]; SQL [n/a]
```

### Root Cause

- `UserEntity.displayPreferences` (`dao/model/UserEntity.java`) is a `Map<String, Object>`
  mapped with `@Convert(converter = JsonMapConverter.class)` on a `jsonb` column.
- `JsonMapConverter` (`config/persistence/JsonMapConverter.java`) implements
  `AttributeConverter<Map<String, Object>, Object>`. The database-side (relational) type is the
  bare `java.lang.Object`, for which Hibernate has NO resolvable JDBC type.
- Because the column has no resolvable JDBC type, Hibernate cannot bind ANY value for it —
  this affects both a null bind and an empty-map (`{}`) bind. It is a JDBC-type-resolution
  problem, not a null-value problem.
- Evidence: the initial mapper-default fix (default null → `Map.of()`) moved the failure from
  `null [Unknown Types value.]` to `{} [Unsupported Types value: 545,108,554]` — the empty map
  still could not bind, proving the column type itself was unresolvable.
- The `locale` value ("pl") is NOT the cause: `locale` is a plain `VARCHAR(5)` String column
  and "pl" is a supported locale. "Parameter #4" is a Hibernate INSERT bind ordinal, not a
  JSON field index.
- The JDBC URL does not set `stringtype=unspecified`, and the project has no
  hypersistence-utils dependency, so neither of those alternative mechanisms is in play.
- Fix: map the field with `@JdbcTypeCode(SqlTypes.JSON)` (dropping the `@Convert` converter on
  the entity) so Hibernate has a concrete JSON JDBC type for the column and binds null as a
  typed `jsonb` null — the pattern `AuditLogEntity` already uses.

### Current Behavior (Defect)

1.1 WHEN a user is created via `POST /api/users` with a body that omits `displayPreferences`
(or sends it as `null`) THEN the system SHALL currently throw
`InvalidDataAccessResourceUsageException` ("Unable to bind parameter ... null [Unknown Types
value.]") and return HTTP 500, so no user is created.

1.2 WHEN a user is updated via `PUT /api/users/{id}` with a body that omits
`displayPreferences` on an entity whose stored `displayPreferences` is `null` THEN the system
SHALL currently fail to bind the null `jsonb` parameter with the same "Unknown Types value"
error.

### Expected Behavior (Correct)

2.1 WHEN a user is created via `POST /api/users` with a body that omits `displayPreferences`
THEN the system SHALL persist the user successfully (HTTP 201) with `display_preferences`
stored as SQL `NULL` (which reads back as `{}` at the API), because `@JdbcTypeCode(SqlTypes.JSON)`
lets Hibernate bind a null value as a typed `jsonb` null.

2.2 WHEN a user is created via `POST /api/users` with a non-null `displayPreferences` map
THEN the system SHALL persist the user successfully and store the map as valid `jsonb`.

2.3 WHEN a user is updated via `PUT /api/users/{id}` on an entity whose `display_preferences`
is `NULL` and the update body omits `displayPreferences` THEN the system SHALL complete the
update successfully without the "Unknown Types value" error.

2.4 WHEN either the null or the non-null `displayPreferences` path is exercised THEN the
system SHALL bind the `jsonb` column using a concrete, Hibernate-resolvable JDBC type so that
null values no longer raise "Unknown Types value".

### Unchanged Behavior (Regression Prevention)

3.1 WHEN a user is created or updated with a non-null `displayPreferences` map THEN the
system SHALL CONTINUE TO serialize the map to `jsonb` and reject a plain `character varying`
value (the FOR-02-07-users-ui-fixes 2.7 behavior SHALL NOT regress).

3.2 WHEN `displayPreferences` is read back via `GET /api/users/{id}/display-preferences`
THEN the system SHALL CONTINUE TO deserialize stored `jsonb` into a `Map<String, Object>`
(returning an empty object when the stored value is null).

3.3 WHEN `DisplayPreferencesController.updatePreferences` writes a non-null preferences map
THEN the system SHALL CONTINUE TO persist it as `jsonb` without error.

3.4 WHEN a user is created with valid `name`, `email`, `phone`, `roleId`, and `locale`
("ru" or "pl") THEN the system SHALL CONTINUE TO validate email uniqueness, validate locale,
enforce the ADMIN-role prohibition, resolve the role, persist the entity, and issue the
invite in `afterCreate` (existing FOR-02-07 semantics SHALL NOT regress).

3.5 WHEN existing rows already contain `jsonb` display preferences THEN the system SHALL
CONTINUE TO read them correctly after the fix (no data migration required).

3.6 WHEN `GET /api/users/{id}/display-preferences` is called for a user created without
preferences THEN the system SHALL CONTINUE TO return an empty object `{}` (whether the stored
value is `{}` or `NULL`), so the API response is unchanged from the caller's perspective.
