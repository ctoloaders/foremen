# FOR-01: Структура бекенда

## Источник паттернов

Архитектура основана на проекте `~/projects/tickets/api-events/` — кастомный CRUD-фреймворк на Spring Boot с многоуровневой абстракцией.

## Ключевые компоненты фреймворка (по результатам анализа)

### 1. Gradle + Spring Boot конфигурация
- Gradle-проект с dependency management
- Spring Boot 4 (upgrade с 2.5.6 в tickets)
- Java 25 (upgrade с 17 в tickets)
- Lombok, MapStruct, Liquibase
- PostgreSQL (вместо MySQL в tickets)

### 2. BaseEntity — основа всех сущностей
- `id` (Long, auto-generated)
- `createdDate`, `createdBy` — аудит создания
- `updatedDate`, `updatedBy` — аудит изменения
- `@MappedSuperclass` + `AuditingEntityListener`

### 3. Многоуровневый маппинг (MapStruct)
- `ServiceToDaoMapper<DaoModel, ServiceModel, ServiceExtendedModel>` — маппинг между слоями service↔dao
- `ControllerToServiceMapper<...>` — маппинг между controller↔service (10 generic-параметров!)
- `I18nPropertiesMapper` — обработка i18n полей при маппинге
- `@BeanMapping(nullValuePropertyMappingStrategy = IGNORE)` для partial updates

### 4. Generics-based CRUD (3 уровня)
- **DAO:** `AdminDao<DaoModel, ID>` extends `PagingAndSortingRepository` + `JpaSpecificationExecutor`
- **Service:** `AdminService<ServiceModel, ServiceExtendedModel, DaoModel, ID>` — create/update/delete/find + audit + i18n translations
- **Controller:** `AdminController<...10 params...>` — REST endpoints: POST, PUT, GET, GET/{id}, GET/extended, DELETE, bulk operations

### 5. CustomQueryBuilder — динамические фильтры
- Парсинг строковых query-параметров в JPA Specifications
- Поддержка custom query parts и IN-параметров
- `addPermissionConditions(query)` — автоматическое добавление условий доступа

### 6. DAO Views — read-only проекции
- `ReadOnlyAdminDao` с `getViewSelectQuery()` для SQL views
- View-entities для статистик и агрегаций (`*ViewEntity.java`)
- Отделение read-модели от write-модели

### 7. Access Control (RBAC)
- Пакет `access/control/core` — attribute-based annotations
- `@AccessControlOperation(create/read/update/delete/translate)`
- `@EntityId` — маркер entity ID для access control
- `PermissionFilter` — фильтрация данных по правам
- Роли + features + операции на ресурсах

### 8. i18n (интернационализация)
- В БД: каждое i18n-поле дублируется по языкам (propertyEN, propertyRU, и т.д.)
- Автоматический перевод через GPT service
- `I18nConfig` + `LanguageHeaderLocaleInterceptor`
- messages.properties файлы для UI текстов
- Endpoint `/i18n` — список переводимых полей сущности

### 9. Audit система
- Автоматическое сохранение snapshot + changes при update
- `AuditEntity` с JSON хранением изменений
- Endpoint `/audit/{id}` на каждом контроллере

### 10. Миграции БД (Liquibase)
- `database_files/changelog.xml` → `database_files/tables/*.sql`
- Встроенная поддержка в Gradle

### 11. Exception Handling
- `EventsApiException` с HTTP status + i18n message code
- Controller advice для единообразных ответов

## Стадии работы (дочерние спеки)

Спеки выстроены в порядке зависимостей. Каждая следующая опирается на совокупность предыдущих.

| # | Спека | Статус | Что реализовано |
|---|-------|--------|-----------------|
| 01 | FOR-01-01-gradle | ✅ | Gradle 9.1 + Spring Boot 4.0 + Java 25, все зависимости (Lombok, MapStruct, jqwik, Testcontainers, Liquibase, SpringDoc, Caffeine, Jackson JSR310), annotation processors, docker-compose PostgreSQL 16 |
| 02 | FOR-01-02-base-entity | ✅ | `BaseEntity` (@MappedSuperclass, id/createdDate/modifiedDate), `JpaAuditingConfig`, `ForemenApplication` |
| 03 | FOR-01-03-exception | ✅ | `ForemenApiException` (status + messageCode + params), `ForemenControllerAdvice` (validation, data integrity, access denied, 500), `ErrorResponse` DTO, `MessageResolver`, messages.properties (PL/RU) |
| 04 | FOR-01-04-mapstruct | ✅ | `ServiceToDaoMapper`, `ControllerToServiceMapper` (10 generic params), `I18nPropertiesMapper`, `ForemenMapperConfig` (IGNORE nulls, disable builders), `@I18nBlankToNull` qualifier |
| 05 | FOR-01-05-crud-dao | ✅ | `AdminDao` (JpaRepository + JpaSpecificationExecutor + custom bulk ops), `ReadOnlyAdminDao` (view support, findAllByIdIn), `QueryParser` (RSQL-like DSL → JPA Specification) |
| 06 | FOR-01-06-crud-service | ✅ | `ReadOnlyAdminService` (find/findExtended/count + i18n sort/filter + ACL hooks + view queries), `AdminService` (create/update/delete/bulk + universal audit with JSONB snapshots + setPropertiesToNull + softDelete) |
| 07 | FOR-01-07-crud-controller | ✅ | `AdminController` (11 REST endpoints as default methods), `AdminReadOnlyController`, `ForemenLocaleInterceptor` + `ForemenWebMvcConfig` |
| 08 | FOR-01-10-config | ✅ | `SecurityConfig` (permitAll stub, CSRF off, stateless, no frameOptions), `CacheConfig` (CaffeineCacheManager с configurable spec), `CorsConfig` (WebMvcConfigurer, configurable origins), application.yml properties |

*Примечание: FOR-01-08-audit и FOR-01-09-liquibase были реализованы в рамках FOR-01-06/07 и FOR-01-01 соответственно, отдельные спеки не создавались.*

### Граф зависимостей:
```
01-gradle → 02-base-entity → 03-exception → 04-mapstruct → 05-crud-dao → 06-crud-service → 07-crud-controller
                                                                                                    ↑
                                                                           08-config ───────────────┘
```

**FOR-01 полностью завершён.** Audit и Liquibase были покрыты в рамках FOR-01-06/07 и FOR-01-01.

## Адаптация под Foremen

| Аспект | tickets (исходный) | Foremen (целевой) |
|--------|-------------------|-------------------|
| Java | 17 | 25 |
| Spring Boot | 2.5.6 | 4.x |
| БД | MySQL | PostgreSQL |
| javax.* | javax.persistence | jakarta.persistence |
| Cloud | AWS | Google Cloud |
| i18n языки | EN, RU, PL, UA, BY | RU, PL (расширяемо) |
| Пакет | com.tickets | com.foremen |
| Audit | JSON в MySQL | JSONB в PostgreSQL |
