# Implementation Plan: User Entity (FOR-02-05-user-entity)

## Overview

Implement the User JPA entity with full CRUD stack following the FOR-01 framework. User has no i18n fields. JSONB handled via AttributeConverter. Soft-delete via active flag.

## Tasks

- [ ] 1. Create Liquibase changeset 008-create-users.xml
  - Create `users` table: id, name, email (unique), phone, role_id (FK), active, locale, display_preferences (JSONB), created_date, created_by, updated_date, updated_by
  - Seed USERS resource in resources table
  - Seed ADMIN CRUD permissions for USERS resource
  - Register in changelog.xml
  - _Requirements: 1.1–1.7, 4.1, 4.2, 7.3_

- [ ] 2. Create JsonMapConverter
  - JPA AttributeConverter for Map<String, Object> ↔ JSONB string
  - Handles null gracefully
  - _Requirements: 5.1–5.4_

- [ ] 3. Create UserEntity
  - Extends BaseEntity, @Table(name = "users")
  - Fields: name, email, phone, role (ManyToOne), active, locale, displayPreferences (@Convert)
  - _Requirements: 1.1–1.7, 7.1_

- [ ] 4. Create UserDao
  - Extends AdminDao<UserEntity, Long>
  - Add findByEmail(String email) for uniqueness check
  - _Requirements: 7.2_

- [ ] 5. Create service models
  - UserServiceModel: id, name, email, phone, roleId, roleName, active, locale
  - UserServiceExtendedModel: id, name, email, phone, roleId, active, locale, displayPreferences
  - _Requirements: 7.2_

- [ ] 6. Create UserServiceMapper
  - Extends ServiceToDaoMapper<UserEntity, UserServiceModel, UserServiceExtendedModel>
  - getI18nSupportedProperties() returns Set.of() (no i18n)
  - Custom mappings for role ↔ roleId
  - _Requirements: 7.2, 7.4_

- [ ] 7. Create UserService
  - Implements AdminService
  - Override deleteById → soft-delete (set active=false)
  - Validate email uniqueness on create/update
  - Validate role exists on create/update
  - Validate locale is one of ('ru', 'pl', 'en')
  - _Requirements: 2.1–2.6, 3.1–3.5, 6.1, 6.2_

- [ ] 8. Create controller DTOs
  - UserDtoModel, UserDtoExtendedModel
  - UserCreateRequest (with @NotBlank, @Email validations)
  - UserCreateResponse, UserUpdateRequest, UserUpdateResponse
  - _Requirements: 2.1–2.4, 3.1–3.2_

- [ ] 9. Create UserControllerMapper
  - Extends ControllerToServiceMapper with User type params
  - _Requirements: 7.2_

- [ ] 10. Create UserController
  - Implements AdminController, mapped to /api/users
  - _Requirements: 2.1–2.6, 6.2_

- [ ] 11. Verify build compiles

- [ ] 12. Commit and update PROGRESS.md

---

*Created: August 2026*
