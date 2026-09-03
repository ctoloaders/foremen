# Bugfix Design Document

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

## Chosen Fix (mapper default)

Prevent the entity from ever holding a `null` `displayPreferences`, so the converter's null
path — the one Hibernate cannot bind — is never exercised. When the incoming value is `null`,
the mapper defaults it to an empty map (`Map.of()`); the converter then always receives a
non-null map and always returns a typed `PGobject`, which binds cleanly.

### Mapper change

`UserServiceMapper` (`service/model/mapper/UserServiceMapper.java`) is an abstract MapStruct
mapper with an existing `@AfterMapping resolveRole(...)` hook that runs for both the create
mapping (`toCreateDaoModel`) and the update mapping (`updateFields`). The default is applied in
an `@AfterMapping` hook so it covers both paths in one place:

```java
@AfterMapping
protected void defaultDisplayPreferences(@MappingTarget UserEntity target) {
    if (target.getDisplayPreferences() == null) {
        target.setDisplayPreferences(java.util.Map.of());
    }
}
```

(The existing `resolveRole` `@AfterMapping` is left unchanged; MapStruct invokes all
`@AfterMapping` methods, so a second one is fine. It may also be folded into `resolveRole`, but
a separate, single-purpose method is clearer.)

No change to `JsonMapConverter` and no change to `UserEntity` mappings are required: the
converter's non-null path (returning a `jsonb`-typed `PGobject`) is already correct, and it is
now the only path taken for `displayPreferences`.

### Behavior note (stored value: `{}` instead of `NULL`)

With this fix, a user created without `displayPreferences` stores an empty `jsonb` object
(`{}`) rather than SQL `NULL`. This is a deliberate, accepted change:

- `GET /api/users/{id}/display-preferences` already coalesces a null map to `{}`
  (`DisplayPreferencesController` returns `prefs != null ? prefs : Map.of()`), so an empty map
  and a null map produce the identical API response — no caller-visible difference.
- `JsonMapConverter.convertToEntityAttribute` deserializes `{}` back to an empty map cleanly.
- Existing rows with `NULL` are untouched and still read as `{}` at the API. No migration.

If storing SQL `NULL` (not `{}`) were a hard requirement, the alternative below (concrete
converter relational type) would be needed instead; the user selected the mapper-default
approach.

## Existing test guards (unchanged)

Both FOR-02-07-users-ui-fixes converter tests remain valid as-is and MUST keep passing:

- `JsonMapConverterBugConditionTest` — still asserts `convertToDatabaseColumn(nonNullMap)`
  returns a `PGobject` typed `jsonb`. The converter is unchanged, so this holds.
- `JsonMapConverterPreservationTest` — null→null and round-trip assertions still hold.

## Alternatives considered (not chosen)

- Converter relational type `Object`→`String` + `@JdbcTypeCode(SqlTypes.JSON)`: fixes the null
  bind at the persistence layer and preserves SQL `NULL` for absent preferences, but touches
  the converter and requires updating `JsonMapConverterBugConditionTest`.
- Drop `JsonMapConverter`, use `@JdbcTypeCode(SqlTypes.JSON)` on the `Map` field: matches
  `AuditLogEntity` but deletes the converter's regression tests.
- `stringtype=unspecified` on the JDBC URL: too broad a blast radius (affects every statement).

The mapper-default fix was chosen for its minimal footprint (one mapper method, no converter or
entity change) and because the `{}`-vs-`NULL` difference is invisible at the API.

## Impact and Scope

- File changed: `service/model/mapper/UserServiceMapper.java` (add one `@AfterMapping` method).
  MapStruct regenerates `UserServiceMapperImpl` on build.
- No change to `JsonMapConverter`, `UserEntity`, DTOs, or existing tests.
- No database migration; existing `NULL` rows read as `{}` at the API.
- No API contract change; no change to validation, ADMIN-role enforcement, invite issuance,
  or locale handling.

## Verification Strategy

1. Build the backend (`./gradlew build`) so the annotation processor regenerates
   `UserServiceMapperImpl` with the new `@AfterMapping` default and compilation succeeds.
2. Run existing backend tests, including `JsonMapConverterPreservationTest`,
   `JsonMapConverterBugConditionTest` (both unchanged), and
   `DisplayPreferencesControllerIntegrationTest` (null-preferences read path) to confirm no
   regression.
3. Run the API test cases in `test-cases.md` against the Dockerized stack: create a user
   without `displayPreferences` (expect 201), create a user with `displayPreferences`
   (expect 201 + stored jsonb), read them back, and exercise the update path.
