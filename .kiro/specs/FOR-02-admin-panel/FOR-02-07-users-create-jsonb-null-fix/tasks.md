# Implementation Plan

- [x] 1. ~~Default displayPreferences to Map.of() in the mapper when null~~ (SUPERSEDED — did not fix root cause; empty map still failed to bind)

- [ ] 2. Map displayPreferences with @JdbcTypeCode(SqlTypes.JSON) and remove the mapper default
  - In `dao/model/UserEntity.java`, replace `@Convert(converter = JsonMapConverter.class)` on `displayPreferences` with `@JdbcTypeCode(SqlTypes.JSON)` (add `org.hibernate.annotations.JdbcTypeCode` and `org.hibernate.type.SqlTypes` imports, remove the `JsonMapConverter` import).
  - In `service/model/mapper/UserServiceMapper.java`, remove the `defaultDisplayPreferences` `@AfterMapping` method added by the previous fix (no longer needed).
  - Keep the `JsonMapConverter` class and its tests unchanged.
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 3.1, 3.2, 3.3, 3.5, 3.6_
