# Requirements Document

## Introduction

This specification defines the MapStruct configuration and base mapper interfaces for the Foremen backend. The system provides: a multi-layered mapping architecture with generic base interfaces for DAO↔Service and Service↔Controller conversions, locale-aware i18n field processing during mapping, qualifier annotations for method disambiguation, and a global MapStruct configuration class.

The pattern is taken from the `tickets/api-events` project (`ServiceToDaoMapper`, `ControllerToServiceMapper`, `I18nPropertiesMapper`) and adapted for Spring Boot 4, Jakarta EE, Java 25, PostgreSQL, and a two-language i18n model (PL, RU).

## Glossary

- **ServiceToDaoMapper**: A generic base interface for mapping between DAO entities and Service-layer models. Parameterized by `<DaoModel, ServiceModel, ServiceExtendedModel>`.
- **ControllerToServiceMapper**: A generic base interface for mapping between Controller DTOs and Service-layer models. Parameterized by `<ServiceModel, ServiceExtendedModel, DtoModel, DtoExtendedModel, CreateRequestModel, CreateResponseModel, UpdateRequestModel, UpdateResponseModel>`.
- **I18nPropertiesMapper**: A base interface that provides `@AfterMapping` methods for copying locale-specific i18n fields during mapping (e.g., `nameRU` → `name` based on current locale).
- **ForemenMapperConfig**: A `@MapperConfig` annotated class that defines default MapStruct settings (component model, builder strategy) shared across all mappers.
- **QualifierAnnotation**: A custom annotation (`@ToServiceModel`, `@ToExtendedServiceModel`) used as a MapStruct `@Qualifier` to disambiguate between multiple mapping methods with similar signatures.
- **PartialUpdate**: An update operation where only non-null fields from the source overwrite target fields, achieved via `@BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)`.
- **Locale**: The language context (PL or RU) determined from the HTTP request, used to select the correct i18n field suffix during mapping.

## Requirements

### Requirement 1: MapStruct Global Configuration (ForemenMapperConfig)

**User Story:** As a developer, I want a centralized MapStruct configuration class with default settings, so that all concrete mappers share consistent behavior without repeating configuration annotations.

#### Acceptance Criteria

1. THE ForemenMapperConfig SHALL be defined as a Java interface annotated with `@MapperConfig` and reside in the package `com.foremen.config.mapper`.
2. THE ForemenMapperConfig SHALL specify `componentModel = MappingConstants.ComponentModel.SPRING` as the default component model.
3. THE ForemenMapperConfig SHALL specify `builder = @Builder(disableBuilder = true)` to disable builder-based mapping (Lombok setters are used instead).
4. THE ForemenMapperConfig SHALL specify `nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE` as the default null-handling strategy.
5. THE build.gradle SHALL contain the compiler argument `-Amapstruct.defaultComponentModel=spring` in the `compileJava` task options.
6. THE build.gradle SHALL declare the annotation processor dependencies in the following order: `lombok`, then `lombok-mapstruct-binding:0.2.0`, then `mapstruct-processor`.
7. WHEN a concrete mapper interface is annotated with `@Mapper(config = ForemenMapperConfig.class)`, THE mapper SHALL inherit all default settings from ForemenMapperConfig without repeating the `componentModel`, `builder`, or `nullValuePropertyMappingStrategy` annotations.

### Requirement 2: ServiceToDaoMapper Base Interface

**User Story:** As a developer, I want a generic base mapper interface for DAO↔Service conversions, so that every entity module can extend it and get consistent mapping methods without boilerplate.

#### Acceptance Criteria

1. THE ServiceToDaoMapper SHALL be a generic interface with type parameters `<DaoModel, ServiceModel, ServiceExtendedModel>` in the package `com.foremen.mapper`.
2. THE ServiceToDaoMapper SHALL extend `I18nPropertiesMapper<ServiceModel, DaoModel>`.
3. THE ServiceToDaoMapper SHALL declare a method `toServiceModel(DaoModel source)` annotated with `@ToServiceModel` returning `ServiceModel`.
4. THE ServiceToDaoMapper SHALL declare a method `toServiceExtendedModel(DaoModel source)` annotated with `@ToExtendedServiceModel` returning `ServiceExtendedModel`.
5. THE ServiceToDaoMapper SHALL declare a method `toCreateDaoModel(ServiceModel source)` returning `DaoModel` with `@Mapping(target = "id", ignore = true)`.
6. THE ServiceToDaoMapper SHALL declare a method `updateFields(ServiceModel source, @MappingTarget DaoModel target)` annotated with `@BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)` returning void.
7. THE ServiceToDaoMapper SHALL declare a method `getI18nSupportedProperties()` returning `Set<String>` where each element is a base field name (without locale suffix) that has corresponding `{name}RU` and `{name}PL` fields on the DaoModel.
8. THE ServiceToDaoMapper SHALL declare a default method named `processI18nEmptyValues` annotated with `@AfterMapping` that iterates over `getI18nSupportedProperties()` and for each property whose locale-specific field (e.g., `{name}RU` or `{name}PL`) is null or blank, sets that locale-specific field to null on the resulting DaoModel.
9. IF `getI18nSupportedProperties()` returns an empty set, THEN THE `processI18nEmptyValues` method SHALL complete without modifying any fields on the target DaoModel.
10. THE ServiceToDaoMapper SHALL use `@Mapping(target = "id", ignore = true)` exclusively on `toCreateDaoModel` to ensure newly created entities do not carry a pre-assigned identifier from the service layer.

### Requirement 3: ControllerToServiceMapper Base Interface

**User Story:** As a developer, I want a generic base mapper interface for Controller↔Service conversions, so that REST layer DTOs are consistently transformed to and from service models.

#### Acceptance Criteria

1. THE ControllerToServiceMapper SHALL be a generic interface with type parameters `<ServiceModel, ServiceExtendedModel, DtoModel, DtoExtendedModel, CreateRequestModel, CreateResponseModel, UpdateRequestModel, UpdateResponseModel>` in the package `com.foremen.mapper`.
2. THE ControllerToServiceMapper SHALL declare a method `toServiceModel(CreateRequestModel source)` returning `ServiceModel` with `@Mapping(target = "id", ignore = true)`.
3. THE ControllerToServiceMapper SHALL declare a method `toUpdateServiceModel(UpdateRequestModel source)` returning `ServiceModel` without ignoring the `id` field (the `id` is expected to be mapped from the request model if present).
4. THE ControllerToServiceMapper SHALL declare a method `toDto(ServiceModel source)` returning `DtoModel`.
5. THE ControllerToServiceMapper SHALL declare a method `toExtendedDto(ServiceExtendedModel source)` returning `DtoExtendedModel`.
6. THE ControllerToServiceMapper SHALL declare a method `toCreateResponse(ServiceModel source)` returning `CreateResponseModel`.
7. THE ControllerToServiceMapper SHALL declare a method `toUpdateResponse(ServiceModel source)` returning `UpdateResponseModel`.
8. THE ControllerToServiceMapper SHALL be a plain Java interface without `@Mapper` or `@MapperConfig` annotations (concrete mappers extending it apply their own `@Mapper(config = ForemenMapperConfig.class)` annotation).

### Requirement 4: I18nPropertiesMapper Interface

**User Story:** As a developer, I want automatic locale-aware field copying during mapping, so that i18n entities with per-language fields (e.g., `nameRU`, `namePL`) correctly populate the display field (`name`) based on the current request locale.

#### Acceptance Criteria

1. THE I18nPropertiesMapper SHALL be a generic interface with type parameters `<TargetModel, SourceModel>` in the package `com.foremen.mapper`.
2. THE I18nPropertiesMapper SHALL declare a default `@AfterMapping` method `processI18n(@MappingTarget TargetModel target, SourceModel source)` that iterates over the set returned by `getI18nSupportedProperties()` and, for each property name, copies the locale-specific field value from the source into the corresponding base field on the target.
3. THE I18nPropertiesMapper SHALL determine the current locale from `LocaleContextHolder.getLocale()`.
4. THE I18nPropertiesMapper SHALL support exactly two locales: PL and RU. IF the current locale is not RU, THEN THE I18nPropertiesMapper SHALL default to PL.
5. WHEN the current locale is RU, THE I18nPropertiesMapper SHALL copy the value of `{property}RU` into `{property}` on the target object.
6. WHEN the current locale is PL (or any non-RU locale), THE I18nPropertiesMapper SHALL copy the value of `{property}PL` into `{property}` on the target object.
7. THE I18nPropertiesMapper SHALL use Java reflection to access and set i18n properties dynamically based on the set returned by `getI18nSupportedProperties()`. IF a reflection operation fails (field not found or inaccessible), THEN THE I18nPropertiesMapper SHALL throw a runtime exception indicating the property name and the source class.
8. THE I18nPropertiesMapper SHALL declare a default helper method `getInCurrentLocale(String basePropertyName, Object source)` that resolves the current locale, appends the locale suffix (RU or PL) to the base property name, and returns the value of that field from the source object via reflection.
9. THE I18nPropertiesMapper SHALL declare an abstract method `getI18nSupportedProperties()` returning `Set<String>` that concrete mappers must implement to specify which base property names have locale-specific variants.
10. WHEN a locale-specific field value is null, THE I18nPropertiesMapper SHALL set the base field on the target to null.

### Requirement 5: Qualifier Annotations

**User Story:** As a developer, I want custom qualifier annotations for mapper methods, so that MapStruct can disambiguate between multiple methods returning the same type in dependency injection contexts.

#### Acceptance Criteria

1. THE project SHALL contain an annotation `@ToServiceModel` in the package `com.foremen.mapper.qualifier`.
2. THE `@ToServiceModel` annotation SHALL be annotated with `@Qualifier` (from `org.mapstruct`).
3. THE `@ToServiceModel` annotation SHALL have `@Target(ElementType.METHOD)` and `@Retention(RetentionPolicy.CLASS)`.
4. THE project SHALL contain an annotation `@ToExtendedServiceModel` in the package `com.foremen.mapper.qualifier`.
5. THE `@ToExtendedServiceModel` annotation SHALL be annotated with `@Qualifier` (from `org.mapstruct`).
6. THE `@ToExtendedServiceModel` annotation SHALL have `@Target(ElementType.METHOD)` and `@Retention(RetentionPolicy.CLASS)`.
7. THE `@ToServiceModel` and `@ToExtendedServiceModel` annotations SHALL each be defined as `public @interface` with an empty body (no attributes).
8. IF a mapper interface contains two or more methods with the same return type but different mapping purposes, THEN THE qualified method SHALL be selectable via `qualifiedBy` attribute in `@Mapping` without compilation errors.

### Requirement 6: Partial Update Mapping Strategy

**User Story:** As a developer, I want partial update (PATCH) support in mappers, so that only non-null fields from an update request overwrite existing entity fields while preserving other values.

#### Acceptance Criteria

1. WHEN `updateFields` is called on ServiceToDaoMapper with a source containing non-null properties, THE mapper SHALL copy each non-null source property value to the corresponding target property with the same name.
2. IF a source field is null, THEN THE mapper SHALL leave the corresponding target field unchanged, preserving its existing value.
3. THE `updateFields` method SHALL be annotated with `@BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)`.
4. THE `updateFields` method SHALL accept the target as a `@MappingTarget` parameter, modifying the object in-place and returning void.
5. WHEN `updateFields` is called, THE mapper SHALL NOT overwrite the `id` field on the target, regardless of whether the source contains an `id` value.

### Requirement 7: Concrete Mapper Convention

**User Story:** As a developer, I want a clear convention for creating concrete mappers, so that new entity modules follow a consistent pattern and integrate with Spring DI automatically.

#### Acceptance Criteria

1. WHEN a concrete mapper is created, THE mapper interface SHALL be annotated with `@Mapper(config = ForemenMapperConfig.class)`.
2. IF a concrete mapper requires dependent mappers (i.e., its mapping methods reference types mapped by other mapper interfaces), THEN THE `@Mapper` annotation SHALL specify those mapper interfaces in the `uses` attribute.
3. IF a concrete mapper extends ServiceToDaoMapper, THEN THE mapper SHALL override `getI18nSupportedProperties()` to return the set of i18n field names for that entity, where each entry is the base property name (e.g., `"name"`) corresponding to locale-suffixed fields (`nameRU`, `namePL`) on the DAO model.
4. THE MapStruct annotation processor SHALL generate implementation classes annotated with `@Component` (via `componentModel = "spring"`).
5. THE generated mapper implementations SHALL be injectable via Spring constructor injection or `@Autowired`.
6. IF a concrete mapper extends ServiceToDaoMapper for an entity that has no i18n fields, THEN THE mapper SHALL override `getI18nSupportedProperties()` to return an empty `Set`.

### Requirement 8: Correctness Properties

**User Story:** As a developer, I want formal correctness guarantees for the mapping system, so that I can rely on consistent and lossless data transformation across layers.

#### Acceptance Criteria

1. FOR ALL non-null fields in a ServiceModel that have a field with the same name and compatible type in the DaoModel (excluding the `id` field), WHEN `toCreateDaoModel` is called, THE resulting DaoModel field SHALL satisfy `Objects.equals(sourceFieldValue, resultFieldValue)` for each such field (invariant: field values are preserved during mapping).
2. FOR ALL DaoModel instances, WHEN `toServiceModel` is called followed by `toCreateDaoModel`, THE resulting DaoModel SHALL have field values satisfying `Objects.equals(original, result)` for all fields that exist in both DaoModel and ServiceModel, excluding `id` and locale-variant fields (`{field}RU`, `{field}PL`) whose values are determined by locale context rather than direct mapping (round-trip: DAO → Service → DAO preserves non-derived data).
3. FOR ALL ServiceModel instances with mixed null and non-null fields, WHEN `updateFields` is called on a target where every field is non-null, THE target SHALL retain original values (verified via `Objects.equals`) for fields that were null in the source and SHALL have values equal to the source for fields that were non-null in the source (invariant: partial update semantics).
4. FOR ALL i18n-enabled entities where the source object has a non-null `{field}RU` field, WHEN locale is set to RU and `processI18n` is called, THE target base field `{field}` SHALL satisfy `Objects.equals(target.field, source.fieldRU)` (invariant: locale selection is consistent).
5. FOR ALL i18n-enabled entities where the source object has a non-null `{field}PL` field, WHEN locale is set to PL (or any non-RU locale) and `processI18n` is called, THE target base field `{field}` SHALL satisfy `Objects.equals(target.field, source.fieldPL)` (invariant: locale selection is consistent).
6. FOR ALL concrete mappers extending ServiceToDaoMapper, THE `id` field of the result of `toCreateDaoModel` SHALL be null (invariant: new entities never have pre-assigned IDs).
7. FOR ALL concrete mappers, THE generated implementation SHALL be a Spring-managed bean retrievable via `ApplicationContext.getBean(MapperClass.class)` without throwing an exception (invariant: Spring DI integration works).
8. IF a field name returned by `getI18nSupportedProperties()` does not correspond to an existing property on the source or target object, THEN THE `processI18n` method SHALL skip that field without throwing an exception (invariant: i18n processing is resilient to misconfiguration).
