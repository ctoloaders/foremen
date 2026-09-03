# Implementation Plan

- [x] 1. Default displayPreferences to Map.of() in the mapper when null
  - In `service/model/mapper/UserServiceMapper.java`, add an `@AfterMapping` method (with a
    `@MappingTarget UserEntity target`) that sets `target.setDisplayPreferences(Map.of())`
    when `target.getDisplayPreferences() == null`. This runs for `toCreateDaoModel`.
  - Import `java.util.Map`.
  - Leave `JsonMapConverter` and `UserEntity` unchanged.
  - _Requirements: 2.1, 2.2, 2.3, 3.1_
