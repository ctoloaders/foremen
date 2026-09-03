# Bugfix Design Document

## Revision

The original fix shipped here defaulted `displayPreferences` to an empty map (`Map.of()`) in
the mapper so the converter's null path would never be hit. **That fix FAILED in practice.**
Instead of binding cleanly, the empty-map `{}` bind produced a new error:

```
Unable to bind parameter #4 - {} [Unsupported Types value: 545,108,554] [n/a]
```

The mapper default merely moved the failure from the null case
(`null [Unknown Types value.]`) to the empty-map case
(`{} [Unsupported Types value: 545,108,554]`). The real root cause is that
`UserEntity.displayPreferences` was mapped via `@Convert(converter = JsonMapConverter.class)`
whose relational (database-side) type is the bare `java.lang.Object` — Hibernate has **no
resolvable JDBC type** for that column, so *both* a null and an empty-map bind fail.

The fix has been **revised** to give Hibernate a concrete, resolvable JDBC type via
`@JdbcTypeCode(SqlTypes.JSON)` directly on the `Map` field (dropping the `@Convert` converter
on the entity), matching the proven `AuditLogEntity` pattern already in the codebase. The
`JsonMapConverter` class and its tests are kept intact; the entity simply no longer references
it. The sections below reflect this revised fix.

## Overview

`POST /api/users` fails with HTTP 500 when the request omits `displayPreferences`. The failure
originates in how the `UserEntity.displayPreferences` `jsonb` column is bound by Hibernate when
its value is `null`. This document explains the mechanism and specifies the fix, keeping the
non-null `jsonb` behavior established in FOR-02-07-users-ui-fixes (bug 2.7) intact.

## Root Cause Analysis

### The failing bind

`UserEntity` (`dao/model/UserEntity.java`):

```java
@Convert(converter = JsonMapConverter.class)
@Column(columnDefinition = "jsonb")
private Map<String, Object> displayPreferences;
```

`JsonMapConverter` (`config/persistence/JsonMapConverter.java`):

```java
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, Object> {
    @Override
    public Object convertToDatabaseColumn(Map<String, Object> attribute) {
        if (attribute == null) {
            return null;                    // null path
        }
        // non-null path: returns a PGobject typed "jsonb"
    }
}
```

The relational (database-side) type parameter of the converter is `java.lang.Object`. When a
user is created without `displayPreferences`:

1. The create DTO has no `displayPreferences`, and the generated
   `UserServiceMapperImpl.toCreateDaoModel` only copies the map when it is non-null, so
   `UserEntity.displayPreferences` stays `null`.
2. Hibernate builds the INSERT and must bind a value for the `display_preferences` column.
3. `convertToDatabaseColumn(null)` returns `null`.
4. Hibernate needs a JDBC/SQL type to send a typed null. Because the converter's relational
   type is the bare `Object`, Hibernate has no registered `JdbcType` for it and cannot infer
   one from a null value, so it raises:
   `Unable to bind parameter #4 - null [Unknown Types value.]`.

The non-null path avoids this because the returned `PGobject` carries its own `jsonb` type,
so Hibernate does not need to infer one.

### Why the misleading signals are not the cause

- `locale` = "pl" is valid: `locale` is a plain `VARCHAR(5)` column, and "pl" is in
  `UserService.SUPPORTED_LOCALES`. "Parameter #4" is a Hibernate INSERT bind ordinal, not a
  JSON field index.
- No `stringtype=unspecified` is set on the JDBC URL, and there is no hypersistence-utils
  dependency — so neither of those alternative jsonb mechanisms is masking or causing this.

### The proven-working pattern already in the codebase

`AuditLogEntity` (`service/audit/AuditLogEntity.java`) persists `jsonb` without a custom
converter and handles null cleanly:

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "snapshot_before", columnDefinition = "jsonb")
private String snapshotBefore;
```

`@JdbcTypeCode(SqlTypes.JSON)` gives Hibernate an explicit, resolvable JDBC type for the
column, so a null bind is sent as a typed null instead of failing with "Unknown Types value".

## Chosen Fix (@JdbcTypeCode(SqlTypes.JSON))

Give Hibernate a concrete, resolvable JDBC type for the `display_preferences` column by
mapping the `Map` field with `@JdbcTypeCode(SqlTypes.JSON)` instead of the `@Convert`
converter. With an explicit JSON JDBC type, Hibernate binds a null value as a typed `jsonb`
null natively (and binds a non-null map as `jsonb`), so neither the null case nor the
empty-map case fails. This is exactly the pattern `AuditLogEntity` already uses successfully
for its `jsonb` columns.

### Entity change

`UserEntity` (`dao/model/UserEntity.java`):

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(columnDefinition = "jsonb")
private Map<String, Object> displayPreferences;
```

- Removed `@Convert(converter = JsonMapConverter.class)`.
- Added imports `org.hibernate.annotations.JdbcTypeCode` and `org.hibernate.type.SqlTypes`.
- Removed the now-unused `import com.foremen.config.persistence.JsonMapConverter;`.

### Mapper change

`UserServiceMapper` (`service/model/mapper/UserServiceMapper.java`): the
`defaultDisplayPreferences` `@AfterMapping` method added by the failed mapper-default fix is
**removed**. With `@JdbcTypeCode(SqlTypes.JSON)` Hibernate binds null as a typed jsonb null
natively, so the `Map.of()` default is unnecessary. The existing `resolveRole` `@AfterMapping`
is left untouched. The now-unused `import java.util.Map;` is removed (no other code in the file
uses `Map`).

### JsonMapConverter kept (no longer referenced by the entity)

The `JsonMapConverter` class is **not** deleted. It is simply no longer referenced by
`UserEntity`. Its unit tests (`JsonMapConverterPreservationTest`,
`JsonMapConverterBugConditionTest`) are tagged under a different spec and must keep passing;
because the converter class is unchanged, they continue to pass.

### Behavior note (stored value: SQL `NULL` instead of `{}`)

With this fix, a user created without `displayPreferences` stores SQL `NULL` in
`display_preferences` (not `{}`). This is invisible at the API:

- `GET /api/users/{id}/display-preferences` coalesces a null map to `{}`
  (`DisplayPreferencesController` returns `prefs != null ? prefs : Map.of()`), so a SQL `NULL`
  and an empty map produce the identical API response — no caller-visible difference.
- Existing rows (whether `NULL` or `{}`) are untouched and still read as `{}` at the API. No
  migration.

## Existing test guards (unchanged)

Both FOR-02-07-users-ui-fixes converter tests remain valid as-is and MUST keep passing:

- `JsonMapConverterBugConditionTest` — still asserts `convertToDatabaseColumn(nonNullMap)`
  returns a `PGobject` typed `jsonb`. The converter is unchanged, so this holds.
- `JsonMapConverterPreservationTest` — null→null and round-trip assertions still hold.

## Alternatives considered (not chosen)

- **Mapper default (`Map.of()`)** — the originally shipped fix. **Failed:** it moved the error
  from the null bind (`null [Unknown Types value.]`) to the empty-map bind
  (`{} [Unsupported Types value: 545,108,554]`), because the underlying problem is the absence
  of a resolvable JDBC type on the column, not the null value itself.
- Converter relational type `Object`→`String` + `@JdbcTypeCode(SqlTypes.JSON)`: fixes the bind
  at the persistence layer but touches the converter and would require updating
  `JsonMapConverterBugConditionTest`.
- `stringtype=unspecified` on the JDBC URL: too broad a blast radius (affects every statement).

The `@JdbcTypeCode(SqlTypes.JSON)` fix was chosen because it addresses the actual root cause
(no resolvable JDBC type on the column), reuses the proven `AuditLogEntity` pattern, and leaves
`JsonMapConverter` and its tests untouched.

## Impact and Scope

- Files changed:
  - `dao/model/UserEntity.java` — `displayPreferences` now mapped with
    `@JdbcTypeCode(SqlTypes.JSON)` instead of `@Convert(converter = JsonMapConverter.class)`.
  - `service/model/mapper/UserServiceMapper.java` — the `defaultDisplayPreferences`
    `@AfterMapping` method (from the failed fix) is removed. MapStruct regenerates
    `UserServiceMapperImpl` without the default hook on build.
- `JsonMapConverter` is no longer used by the entity, but the class and its tests remain
  unchanged.
- No change to DTOs.
- No database migration; existing `NULL`/`{}` rows read as `{}` at the API.
- No API contract change; no change to validation, ADMIN-role enforcement, invite issuance,
  or locale handling.

## Verification Strategy

1. Build the backend (`./gradlew compileJava` / `build`) so the annotation processor
   regenerates `UserServiceMapperImpl` without the default hook and compilation succeeds.
2. Run existing backend tests. The `JsonMapConverter` class is unchanged, so
   `JsonMapConverterPreservationTest` and `JsonMapConverterBugConditionTest` still pass;
   `DisplayPreferencesControllerIntegrationTest` (null-preferences read path) confirms the
   read path is unaffected.
3. Run the API test cases in `test-cases.md` against the Dockerized stack: create a user
   without `displayPreferences` (expect 201), create a user with `displayPreferences`
   (expect 201 + stored jsonb), read them back, and exercise the update path.
