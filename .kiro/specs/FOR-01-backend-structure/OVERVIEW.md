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

```
FOR-01-01-gradle          ← Фундамент: проект, зависимости
     │
FOR-01-02-base-entity     ← BaseEntity, аудит-поля
     │
FOR-01-03-exception       ← Единая система ошибок (нужна для всего выше)
     │
FOR-01-04-mapstruct       ← MapStruct конфигурация, базовые интерфейсы маппинга
     │
FOR-01-05-crud-dao        ← AdminDao, ReadOnlyAdminDao, DAO views
     │
FOR-01-06-crud-service    ← AdminService, ReadOnlyAdminService, CustomQueryBuilder
     │
FOR-01-07-crud-controller ← AdminController, REST endpoints, валидация
     │
FOR-01-08-audit           ← Audit система (snapshot + changes)
     │
FOR-01-09-liquibase       ← Миграции БД, changelog, конвенции таблиц
     │
     └── FOR-01-10-config  ← Общие конфиги: DataSource, Security stub, OpenAPI, Cache
```

### Независимые ветки (могут делаться параллельно после FOR-01-03):
- **FOR-01-04** (MapStruct) — независим, но нужен для FOR-01-05+
- **FOR-01-09** (Liquibase) — независим, может делаться параллельно с FOR-01-05..08
- **FOR-01-10** (Config) — независим, может делаться параллельно

### Граф зависимостей:
```
01-gradle → 02-base-entity → 03-exception → 04-mapstruct → 05-crud-dao → 06-crud-service → 07-crud-controller → 08-audit
                                    ↓                                                              ↑
                              09-liquibase ─────────────────────────────────────────────────────────┘
                                    ↓
                              10-config ────────────────────────────────────────────────────────────┘
```

## Адаптация под Foremen

| Аспект | tickets (исходный) | Foremen (целевой) |
|--------|-------------------|-------------------|
| Java | 17 | 25 |
| Spring Boot | 2.5.6 | 4.x |
| БД | MySQL | PostgreSQL |
| javax.* | javax.persistence | jakarta.persistence |
| Cloud | AWS | Google Cloud |
| i18n языки | EN, RU, PL, UA, BY | RU, EN (расширяемо) |
| Пакет | com.tickets | com.foremen |
| Audit | JSON в MySQL | JSONB в PostgreSQL |
