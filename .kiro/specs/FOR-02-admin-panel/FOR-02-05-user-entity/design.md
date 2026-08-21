# Design Document: User Entity (FOR-02-05-user-entity)

## Overview

Implements the User entity as a full CRUD resource following the established FOR-01 framework patterns. User has no i18n fields (personal data isn't translatable), so the ServiceToDaoMapper returns an empty set for i18n properties. JSONB support uses a custom JPA AttributeConverter.

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| No i18n on User fields | Name, email, phone are personal data — they don't get translated. ServiceMapper returns `Set.of()` for i18n properties. |
| JSONB via AttributeConverter | Avoids adding Hypersistence Utils dependency. A simple Jackson-based converter handles Map↔JSONB. Keeps dependencies minimal. |
| Soft-delete via `active` flag | Users shouldn't be hard-deleted (audit trail, references from other entities). Deactivation is the standard approach. |
| `locale` as separate column | Frequently queried for notification dispatch (e.g. "send push in user's language"). JSONB would require extraction. |
| display_preferences as JSONB | Schema-flexible UI preferences. Adding new preferences doesn't require DB migration. |
| Delete endpoint sets active=false | Override `deleteById` in UserService to set active=false instead of removing the record. |
| Unique email constraint | Email is the natural identifier for authentication (FOR-03). Enforced at both DB and service level. |

## Architecture

### Entity Relationship

```
┌──────────────────────────────────────────┐
│   UserEntity extends BaseEntity          │
│                                          │
│   - name (VARCHAR 255, NOT NULL)         │
│   - email (VARCHAR 255, UNIQUE NOT NULL) │
│   - phone (VARCHAR 50, nullable)         │
│   - role (ManyToOne → RoleEntity)        │
│   - active (BOOLEAN, default true)       │
│   - locale (VARCHAR 5, default 'ru')     │
│   - displayPreferences (JSONB, nullable) │
└──────────────────────────────────────────┘
         │
         │ ManyToOne
         ▼
┌──────────────────┐
│   RoleEntity     │
└──────────────────┘
```

### Stack Architecture

```
UserController (AdminController)
    ↕ UserControllerMapper
UserService (AdminService)
    ↕ UserServiceMapper
UserDao (AdminDao)
    ↕ JPA/Hibernate
UserEntity (BaseEntity)
    ↕ JsonMapConverter (AttributeConverter)
PostgreSQL (users table, JSONB column)
```

### Package Structure

```
com.foremen
├── config/
│   └── persistence/
│       └── JsonMapConverter.java
├── dao/
│   ├── model/
│   │   └── UserEntity.java
│   └── UserDao.java
├── service/
│   ├── model/
│   │   ├── UserServiceModel.java
│   │   └── UserServiceExtendedModel.java
│   ├── model/mapper/
│   │   └── UserServiceMapper.java
│   └── UserService.java
├── controller/
│   ├── model/
│   │   ├── UserDtoModel.java
│   │   ├── UserDtoExtendedModel.java
│   │   ├── UserCreateRequest.java
│   │   ├── UserCreateResponse.java
│   │   ├── UserUpdateRequest.java
│   │   └── UserUpdateResponse.java
│   ├── model/mapper/
│   │   └── UserControllerMapper.java
│   └── UserController.java
└── database_files/
    └── changesets/
        └── 008-create-users.xml
```

## Components

### 1. JsonMapConverter

```java
@Converter(autoApply = false)
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        if (attribute == null) return null;
        try { return MAPPER.writeValueAsString(attribute); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Cannot serialize JSON", e); }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try { return MAPPER.readValue(dbData, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Cannot deserialize JSON", e); }
    }
}
```

### 2. UserEntity

```java
@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor
public class UserEntity extends BaseEntity {
    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(length = 50)
    private String phone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private RoleEntity role;

    @Column(nullable = false)
    private boolean active = true;

    @Column(length = 5, nullable = false)
    private String locale = "ru";

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> displayPreferences;
}
```

### 3. Service Models

```java
// Summary model for lists (no i18n resolution needed)
public record UserServiceModel(Long id, String name, String email, String phone,
                                Long roleId, String roleName, boolean active, String locale) {}

// Extended model for detail views and create/update
public record UserServiceExtendedModel(Long id, String name, String email, String phone,
                                        Long roleId, boolean active, String locale,
                                        Map<String, Object> displayPreferences) {}
```

### 4. UserServiceMapper

Since User has no i18n fields, the mapper returns `Set.of()` for `getI18nSupportedProperties()`. Custom mappings handle the `role` ↔ `roleId` conversion.

### 5. UserService

Extends AdminService with custom logic:
- Override `deleteById` → sets active=false (soft-delete)
- Override `create` → validates role exists, checks email uniqueness
- Validates locale is one of ('ru', 'pl', 'en')

### 6. Controller DTOs

| DTO | Fields |
|-----|--------|
| UserDtoModel | id, name, email, active, roleName |
| UserDtoExtendedModel | id, name, email, phone, roleId, roleName, active, locale, displayPreferences |
| UserCreateRequest | name, email, phone, roleId, locale, displayPreferences |
| UserCreateResponse | id, name, email, phone, roleId, active, locale, displayPreferences |
| UserUpdateRequest | name, email, phone, roleId, active, locale, displayPreferences |
| UserUpdateResponse | id, name, email, phone, roleId, active, locale, displayPreferences |

### 7. Liquibase Changeset (008-create-users.xml)

- Create `users` table with all columns
- Add FK constraint to roles
- Add unique constraint on email
- Seed USERS resource in resources table
- Seed ADMIN permissions: CRUD on USERS

---

*Created: August 2026*
