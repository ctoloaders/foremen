# Implementation Plan: MapStruct Configuration and Base Mappers (FOR-01-04-mapstruct)

## Overview

Implement the MapStruct mapping infrastructure for the Foremen backend: a global `@MapperConfig` interface, generic base mapper interfaces (`I18nPropertiesMapper`, `ServiceToDaoMapper`, `ControllerToServiceMapper`), qualifier annotations for method disambiguation, and a documented convention for concrete mappers with two separate layer-specific packages.

All source code resides in `foremen-backend/src/main/java/com/foremen/` and tests in `foremen-backend/src/test/java/com/foremen/`.

## Tasks

- [x] 1. Implement ForemenMapperConfig and qualifier annotations
  - [x] 1.1 Create ForemenMapperConfig interface
    - Create `com.foremen.config.mapper.ForemenMapperConfig` as a Java interface
    - Annotate with `@MapperConfig(componentModel = MappingConstants.ComponentModel.SPRING, builder = @Builder(disableBuilder = true), nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)`
    - Empty body — configuration only
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.7_

  - [x] 1.2 Create @ToServiceModel qualifier annotation
    - Create `com.foremen.mapper.qualifier.ToServiceModel` annotation
    - Annotate with `@Qualifier` (org.mapstruct), `@Target(ElementType.METHOD)`, `@Retention(RetentionPolicy.CLASS)`
    - Empty body, public @interface
    - _Requirements: 5.1, 5.2, 5.3, 5.7_

  - [x] 1.3 Create @ToExtendedServiceModel qualifier annotation
    - Create `com.foremen.mapper.qualifier.ToExtendedServiceModel` annotation
    - Annotate with `@Qualifier` (org.mapstruct), `@Target(ElementType.METHOD)`, `@Retention(RetentionPolicy.CLASS)`
    - Empty body, public @interface
    - _Requirements: 5.4, 5.5, 5.6, 5.7_

- [x] 2. Implement base mapper interfaces
  - [x] 2.1 Create I18nPropertiesMapper interface
    - Create `com.foremen.mapper.I18nPropertiesMapper<TargetModel, SourceModel>` generic interface
    - Declare abstract `getI18nSupportedProperties()` returning `Set<String>`
    - Implement default `@AfterMapping` method `processI18n(@MappingTarget TargetModel target, SourceModel source)` that iterates properties, resolves locale, copies locale-specific field to base field via reflection
    - Implement default `getInCurrentLocale(String basePropertyName, Object source)` — resolves locale suffix (RU/PL), appends to property name, returns field value via reflection
    - Implement private static helper `resolveSuffix(Locale)` — returns "RU" for Russian locale, "PL" otherwise (including null locale)
    - Implement private static helpers `getFieldValue`, `setFieldValue`, `findField` for reflection access with class hierarchy traversal
    - Throw `IllegalArgumentException` when field not found, `RuntimeException` wrapping `IllegalAccessException`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 4.10_

  - [x] 2.2 Create ServiceToDaoMapper interface
    - Create `com.foremen.mapper.ServiceToDaoMapper<DaoModel, ServiceModel, ServiceExtendedModel>` extending `I18nPropertiesMapper<ServiceModel, DaoModel>`
    - Declare `toServiceModel(DaoModel source)` annotated with `@ToServiceModel` — returns ServiceModel (locale-resolved display model, processI18n triggered)
    - Declare `toServiceExtendedModel(DaoModel source)` annotated with `@ToExtendedServiceModel` — returns ServiceExtendedModel (direct i18n field copy, NO processI18n)
    - Declare `toCreateDaoModel(ServiceExtendedModel source)` with `@Mapping(target = "id", ignore = true)` — accepts ServiceExtendedModel (full i18n fields)
    - Declare `updateFields(ServiceExtendedModel source, @MappingTarget DaoModel target)` with `@BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)` — accepts ServiceExtendedModel
    - Implement default `@AfterMapping` method `processI18nEmptyValues(@MappingTarget DaoModel target, ServiceExtendedModel source)` — iterates i18n properties, normalizes blank locale fields to null on DaoModel target
    - Implement private static helpers `normalizeLocaleField`, `findDeclaredField`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10_

  - [x] 2.3 Create ControllerToServiceMapper interface
    - Create `com.foremen.mapper.ControllerToServiceMapper<ServiceModel, ServiceExtendedModel, DtoModel, DtoExtendedModel, CreateRequestModel, CreateResponseModel, UpdateRequestModel, UpdateResponseModel>` generic interface
    - Declare `toServiceExtendedModel(CreateRequestModel source)` with `@Mapping(target = "id", ignore = true)` — returns ServiceExtendedModel
    - Declare `toUpdateServiceExtendedModel(UpdateRequestModel source)` — returns ServiceExtendedModel (id mapped from request)
    - Declare `toDto(ServiceModel source)` — returns DtoModel (display DTO from locale-resolved model)
    - Declare `toExtendedDto(ServiceExtendedModel source)` — returns DtoExtendedModel (raw i18n fields)
    - Declare `toCreateResponse(ServiceExtendedModel source)` — returns CreateResponseModel (from extended model)
    - Declare `toUpdateResponse(ServiceExtendedModel source)` — returns UpdateResponseModel (from extended model)
    - No `@Mapper` or `@MapperConfig` annotation — plain interface
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8_

- [x] 3. Checkpoint - Verify compilation
  - Ensure all code compiles with `./gradlew compileJava`, ask the user if questions arise.

- [x] 4. Create test fixtures for property-based testing
  - [x] 4.1 Create DAO and Service layer test fixture models
    - Create `com.foremen.mapper.fixture.TestDaoModel` in test sources — fields: `Long id`, `String nameRU`, `String namePL`, `String descriptionRU`, `String descriptionPL`, `String code`, `Integer quantity` with `@Getter @Setter`
    - Create `com.foremen.mapper.fixture.TestServiceModel` (display model) — fields: `Long id`, `String name`, `String description`, `String code`, `Integer quantity` with `@Getter @Setter`
    - Create `com.foremen.mapper.fixture.TestServiceExtendedModel` as a **standalone class** (NOT extending TestServiceModel) — fields: `Long id`, `String nameRU`, `String namePL`, `String descriptionRU`, `String descriptionPL`, `String code`, `Integer quantity` with `@Getter @Setter`
    - _Requirements: 8.1, 8.2, 8.3_

  - [x] 4.2 Create Controller layer test fixture models
    - Create `com.foremen.mapper.fixture.TestDtoModel` (display DTO) — fields: `Long id`, `String name`, `String description`, `String code`, `Integer quantity` with `@Getter @Setter`
    - Create `com.foremen.mapper.fixture.TestDtoExtendedModel` (extended DTO with i18n) — fields: `Long id`, `String nameRU`, `String namePL`, `String descriptionRU`, `String descriptionPL`, `String code`, `Integer quantity` with `@Getter @Setter`
    - Create `com.foremen.mapper.fixture.TestCreateRequest` (i18n fields) — fields: `String nameRU`, `String namePL`, `String descriptionRU`, `String descriptionPL`, `String code`, `Integer quantity` with `@Getter @Setter`
    - Create `com.foremen.mapper.fixture.TestCreateResponse` (mirrors ExtendedModel, i18n fields) — fields: `Long id`, `String nameRU`, `String namePL`, `String descriptionRU`, `String descriptionPL`, `String code`, `Integer quantity` with `@Getter @Setter`
    - Create `com.foremen.mapper.fixture.TestUpdateRequest` (i18n fields, all nullable) — fields: `Long id`, `String nameRU`, `String namePL`, `String descriptionRU`, `String descriptionPL`, `String code`, `Integer quantity` with `@Getter @Setter`
    - Create `com.foremen.mapper.fixture.TestUpdateResponse` (mirrors ExtendedModel, i18n fields) — fields: `Long id`, `String nameRU`, `String namePL`, `String descriptionRU`, `String descriptionPL`, `String code`, `Integer quantity` with `@Getter @Setter`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7_

  - [x] 4.3 Create TestEntityServiceMapper concrete mapper for testing
    - Create `com.foremen.service.model.mapper.TestEntityServiceMapper` in test sources
    - Annotate with `@Mapper(config = ForemenMapperConfig.class)`
    - Extend `ServiceToDaoMapper<TestDaoModel, TestServiceModel, TestServiceExtendedModel>`
    - Override `getI18nSupportedProperties()` returning `Set.of("name", "description")`
    - _Requirements: 7.1, 7.3, 7.4_

  - [x] 4.4 Create TestEntityControllerMapper concrete mapper for testing
    - Create `com.foremen.controller.model.mapper.TestEntityControllerMapper` in test sources
    - Annotate with `@Mapper(config = ForemenMapperConfig.class)`
    - Extend `ControllerToServiceMapper<TestServiceModel, TestServiceExtendedModel, TestDtoModel, TestDtoExtendedModel, TestCreateRequest, TestCreateResponse, TestUpdateRequest, TestUpdateResponse>`
    - _Requirements: 7.1, 7.4, 7.5_

- [x] 5. Checkpoint - Verify test compilation
  - Ensure all code compiles with `./gradlew compileTestJava`, ask the user if questions arise.

- [x] 6. Property-based tests
  - [x] 6.1 Write property test: Create Mapping Always Produces Null ID
    - **Property 1: Create Mapping Always Produces Null ID**
    - Generate random `TestServiceExtendedModel` instances (some with non-null id, some with null id)
    - Call `toCreateDaoModel(serviceExtendedModel)` on TestEntityServiceMapper
    - Also generate random `TestCreateRequest` instances and call `toServiceExtendedModel(createRequest)` on TestEntityControllerMapper
    - Assert `result.getId() == null` for all generated inputs on both mappers
    - Use `@Property(tries = 100)` with jqwik
    - **Validates: Requirements 2.5, 2.10, 3.2, 8.6**

  - [x] 6.2 Write property test: Field Value Preservation During Create Mapping
    - **Property 2: Field Value Preservation During Create Mapping**
    - Generate random `TestServiceExtendedModel` with non-null i18n fields (`nameRU`, `namePL`, `descriptionRU`, `descriptionPL`) and non-i18n fields (`code`, `quantity`)
    - Call `toCreateDaoModel(serviceExtendedModel)` on TestEntityServiceMapper
    - Assert `Objects.equals(source.getNameRU(), result.getNameRU())`, `Objects.equals(source.getNamePL(), result.getNamePL())`, `Objects.equals(source.getDescriptionRU(), result.getDescriptionRU())`, `Objects.equals(source.getDescriptionPL(), result.getDescriptionPL())`, `Objects.equals(source.getCode(), result.getCode())`, `Objects.equals(source.getQuantity(), result.getQuantity())` for all directly-mapped fields
    - **Validates: Requirements 8.1**

  - [x] 6.3 Write property test: DAO → ServiceExtendedModel → DAO Round-Trip Preserves Data
    - **Property 3: DAO → ServiceExtendedModel → DAO Round-Trip Preserves Data**
    - Generate random `TestDaoModel` with non-null fields
    - Call `toServiceExtendedModel(dao)` then `toCreateDaoModel(serviceExtendedModel)` on TestEntityServiceMapper
    - Assert ALL non-id fields are equal between original and result: `nameRU`, `namePL`, `descriptionRU`, `descriptionPL`, `code`, `quantity`
    - No locale context dependency — this round-trip uses ServiceExtendedModel which carries raw i18n fields
    - **Validates: Requirements 8.2**

  - [x] 6.4 Write property test: Partial Update Semantics (updateFields)
    - **Property 4: Partial Update Semantics**
    - Generate random `TestServiceExtendedModel` with mix of null and non-null i18n fields (`nameRU`, `namePL`, `descriptionRU`, `descriptionPL`) and non-i18n fields (`code`, `quantity`)
    - Generate fully-populated `TestDaoModel` as target
    - Call `updateFields(serviceExtendedModel, target)` on TestEntityServiceMapper
    - Assert: non-null source fields overwrite target (i18n fields directly: `nameRU`→`nameRU`, `namePL`→`namePL`, etc.), null source fields leave target unchanged, id never modified
    - **Validates: Requirements 2.6, 6.1, 6.2, 6.5, 8.3**

  - [x] 6.5 Write property test: I18n Locale Resolution (toServiceModel only)
    - **Property 5: I18n Locale Resolution**
    - Generate random `TestDaoModel` with `nameRU`, `namePL`, `descriptionRU`, `descriptionPL` values (including nulls)
    - Set `LocaleContextHolder` to RU or PL (or other random locales)
    - Call `toServiceModel(dao)` on TestEntityServiceMapper (which triggers `processI18n`)
    - Assert: RU locale → `name == dao.nameRU` and `description == dao.descriptionRU`; non-RU locale → `name == dao.namePL` and `description == dao.descriptionPL`
    - Assert: null locale-specific field → base field is null
    - Additionally verify: `toServiceExtendedModel(dao)` does NOT resolve locale fields — `result.nameRU == dao.nameRU` and `result.namePL == dao.namePL` regardless of locale
    - **Validates: Requirements 4.2, 4.4, 4.5, 4.6, 4.10, 8.4, 8.5**

  - [x] 6.6 Write property test: I18n Empty Value Normalization
    - **Property 6: I18n Empty Value Normalization (processI18nEmptyValues)**
    - Generate random `TestDaoModel` with locale fields containing mix of blank strings, non-blank strings, and nulls
    - Generate random `TestServiceExtendedModel` as source (for @AfterMapping signature)
    - Call `processI18nEmptyValues(target, source)` on TestEntityServiceMapper
    - Assert: blank fields → null, non-blank fields unchanged, null fields remain null
    - **Validates: Requirements 2.8, 2.9**

- [x] 7. Unit tests
  - [x] 7.1 Write unit tests for ForemenMapperConfig structure
    - Verify interface is annotated with `@MapperConfig`
    - Verify `componentModel = MappingConstants.ComponentModel.SPRING`
    - Verify `builder` has `disableBuilder = true`
    - Verify `nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE`
    - _Requirements: 1.1, 1.2, 1.3, 1.4_

  - [x] 7.2 Write unit tests for qualifier annotations
    - Verify `@ToServiceModel` has `@Qualifier`, `@Target(METHOD)`, `@Retention(CLASS)`
    - Verify `@ToExtendedServiceModel` has `@Qualifier`, `@Target(METHOD)`, `@Retention(CLASS)`
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6_

  - [x] 7.3 Write unit tests for I18nPropertiesMapper edge cases
    - Test `processI18n` with non-existent field name → throws `IllegalArgumentException`
    - Test `getInCurrentLocale` with null locale in `LocaleContextHolder` → defaults to PL suffix
    - Test `processI18n` with empty `getI18nSupportedProperties()` → no modifications to target
    - _Requirements: 4.3, 4.4, 4.7, 4.9_

  - [x] 7.4 Write unit tests for ServiceToDaoMapper and ControllerToServiceMapper structure
    - Verify `ServiceToDaoMapper` extends `I18nPropertiesMapper`
    - Verify `ServiceToDaoMapper` method signatures via reflection: `toCreateDaoModel(ServiceExtendedModel)`, `updateFields(ServiceExtendedModel, DaoModel)`, `processI18nEmptyValues(DaoModel, ServiceExtendedModel)`
    - Verify `ControllerToServiceMapper` has no `@Mapper` or `@MapperConfig` annotation
    - Verify `ControllerToServiceMapper` method signatures via reflection: `toServiceExtendedModel(CreateRequestModel)`, `toUpdateServiceExtendedModel(UpdateRequestModel)`, `toCreateResponse(ServiceExtendedModel)`, `toUpdateResponse(ServiceExtendedModel)`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8_

  - [x] 7.5 Write unit tests for processI18nEmptyValues behavior
    - Test with empty `getI18nSupportedProperties()` → no field modifications
    - Test with blank string fields → normalized to null
    - Test with non-blank string fields → unchanged
    - Test with already-null fields → remain null
    - _Requirements: 2.8, 2.9_

- [x] 8. Integration tests
  - [x] 8.1 Write Spring integration test for both test mappers
    - `@SpringBootTest` verifying `ApplicationContext.getBean(TestEntityServiceMapper.class)` succeeds
    - `@SpringBootTest` verifying `ApplicationContext.getBean(TestEntityControllerMapper.class)` succeeds
    - Verify both generated mappers are Spring-managed `@Component` beans
    - _Requirements: 7.4, 7.5, 8.7_

  - [x] 8.2 Write integration test for full create flow
    - CreateRequest → ServiceExtendedModel via TestEntityControllerMapper (`toServiceExtendedModel`, id ignored) → DaoModel via TestEntityServiceMapper (`toCreateDaoModel`, id ignored)
    - Verify end-to-end field propagation: i18n fields pass through directly (`nameRU`→`nameRU`, `namePL`→`namePL`)
    - Verify `processI18nEmptyValues` normalizes blank locale fields
    - _Requirements: 2.5, 2.10, 3.2_

  - [x] 8.3 Write integration test for full update flow
    - UpdateRequest → ServiceExtendedModel via TestEntityControllerMapper (`toUpdateServiceExtendedModel`) → `updateFields(serviceExtendedModel, existingDao)` on TestEntityServiceMapper
    - Verify partial update semantics: non-null i18n fields overwrite, null fields preserved
    - Verify `processI18nEmptyValues` normalizes blank fields after update
    - _Requirements: 6.1, 6.2, 6.5_

  - [x] 8.4 Write integration test for read display flow
    - DaoModel → ServiceModel via TestEntityServiceMapper (`toServiceModel`, processI18n resolves locale) → DtoModel via TestEntityControllerMapper (`toDto`)
    - Set locale to RU, verify `name == dao.nameRU` and `description == dao.descriptionRU` propagated to DtoModel
    - Set locale to PL, verify `name == dao.namePL` and `description == dao.descriptionPL` propagated to DtoModel
    - _Requirements: 4.2, 4.4, 4.5, 4.6_

  - [x] 8.5 Write integration test for read full flow (no locale resolution)
    - DaoModel → ServiceExtendedModel via TestEntityServiceMapper (`toServiceExtendedModel`, NO locale resolution) → ExtendedDTO via TestEntityControllerMapper (`toExtendedDto`)
    - Verify all i18n fields pass through unchanged: `nameRU`→`nameRU`, `namePL`→`namePL`, `descriptionRU`→`descriptionRU`, `descriptionPL`→`descriptionPL`
    - Verify no locale dependency — result is same regardless of `LocaleContextHolder` value
    - _Requirements: 2.4, 3.5_

- [x] 9. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass with `./gradlew test`, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document (Properties 1–6)
- Unit tests validate structural correctness and edge cases
- The implementation language is Java 25 with Spring Boot 4.0.0 and Jakarta EE
- jqwik 1.9.2 is already configured in build.gradle for property-based testing
- MapStruct 1.6.3 with Lombok binding is already configured in build.gradle
- **Key design change:** `toCreateDaoModel` and `updateFields` accept `ServiceExtendedModel` (not `ServiceModel`) because create/update flows work with the full i18n field set
- **Key design change:** `ControllerToServiceMapper` uses `toServiceExtendedModel(CreateRequest)` and `toUpdateServiceExtendedModel(UpdateRequest)` both returning `ServiceExtendedModel`
- **Key design change:** `toCreateResponse` and `toUpdateResponse` accept `ServiceExtendedModel` (not ServiceModel)
- **Key design change:** `TestServiceExtendedModel` is a standalone class (NOT extending TestServiceModel) with its own i18n fields (`nameRU`, `namePL`, etc.)
- **Key design change:** `processI18n` is triggered ONLY for `toServiceModel` (display model); `toServiceExtendedModel` does direct field copy without locale resolution
- Concrete mappers live in two separate layer-specific packages:
  - **ServiceToDao mappers** → `com.foremen.service.model.mapper` (e.g., `TestEntityServiceMapper`)
  - **ControllerToService mappers** → `com.foremen.controller.model.mapper` (e.g., `TestEntityControllerMapper`)
- Test fixtures include both DAO/Service models and Controller-layer models (DTOs, requests, responses) to fully exercise both mapper hierarchies
- Test fixture models reside in `com.foremen.mapper.fixture` (shared), while test concrete mappers reside in their respective layer packages in test sources

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3"] },
    { "id": 1, "tasks": ["2.1", "2.3"] },
    { "id": 2, "tasks": ["2.2"] },
    { "id": 3, "tasks": ["4.1", "4.2"] },
    { "id": 4, "tasks": ["4.3", "4.4"] },
    { "id": 5, "tasks": ["6.1", "6.2", "6.3", "6.4", "6.5", "6.6"] },
    { "id": 6, "tasks": ["7.1", "7.2", "7.3", "7.4", "7.5"] },
    { "id": 7, "tasks": ["8.1", "8.2", "8.3", "8.4", "8.5"] }
  ]
}
```
