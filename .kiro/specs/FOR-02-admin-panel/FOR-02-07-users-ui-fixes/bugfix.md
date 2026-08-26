# Bugfix Requirements Document

## Introduction

This document addresses 5 bugs in the Users management UI feature (FOR-02-07-users-ui). The issues span frontend rendering, architecture (client-side vs server-side search), locale configuration consistency, backend security validation, and a JDBC/PostgreSQL type mismatch when persisting JSONB data.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN the RoleSelect dropdown is opened in the dark theme THEN the system renders the PopoverContent with a transparent background, making the dropdown options unreadable against the page content beneath

1.2 WHEN there are many roles in the system and the user opens the RoleSelect dropdown THEN the system fetches ALL roles upfront (all pages in a loop) regardless of how many exist, causing unnecessary network load and slow initial render

1.3 WHEN the user types a search query in the RoleSelect dropdown THEN the system filters only the already-fetched client-side list, which is incomplete if the full fetch failed or if new roles were added since the initial fetch

1.4 WHEN a user is created or edited with locale value "en" THEN the system accepts it as valid despite English not being a supported application locale (only PL and RU are supported)

1.5 WHEN the UserFormSheet is rendered THEN the system displays an "English (EN)" option in the locale select dropdown, allowing users to choose an unsupported locale

1.6 WHEN a user is created or updated and an admin role is assigned via roleId THEN the system accepts the assignment without restriction, allowing admin privilege escalation through the user management API

1.7 WHEN a new user is created with a non-null displayPreferences map THEN the system throws a PostgreSQL error "column display_preferences is of type jsonb but expression is of type character varying" because the JDBC driver sends the JSON string as VARCHAR instead of a jsonb-compatible type

### Expected Behavior (Correct)

2.1 WHEN the RoleSelect dropdown is opened in the dark theme THEN the system SHALL render the PopoverContent with an opaque background (bg-popover or bg-background class) so options are clearly readable

2.2 WHEN the user opens the RoleSelect dropdown THEN the system SHALL fetch only the first page of roles (size=20) and load additional pages via infinite scroll as the user scrolls down in the list

2.3 WHEN the user types a search query in the RoleSelect dropdown THEN the system SHALL send a debounced (300ms) server-side search request with `query=name~ct~{input}` parameter to GET /api/roles and display paginated results with infinite scroll

2.4 WHEN a user is created or edited THEN the system SHALL only accept locale values "ru" or "pl" (frontend schema enum and backend SUPPORTED_LOCALES set)

2.5 WHEN the UserFormSheet is rendered THEN the system SHALL display only "Polski (PL)" and "Русский (RU)" as locale options without an English option

2.6 WHEN a user is created or updated and the resolved role has code "ADMIN" THEN the system SHALL reject the request with HTTP 409 Conflict and error message "error.user.admin.role.prohibited"

2.7 WHEN a new user is created with a non-null displayPreferences map THEN the system SHALL correctly persist the value as jsonb by using a PGobject with type "jsonb", the @JdbcTypeCode(SqlTypes.JSON) annotation, or `stringtype=unspecified` in the JDBC URL

### Unchanged Behavior (Regression Prevention)

3.1 WHEN the RoleSelect dropdown is opened in the light theme THEN the system SHALL CONTINUE TO render options clearly and maintain existing styling behavior

3.2 WHEN a user is created or edited with locale "ru" or "pl" THEN the system SHALL CONTINUE TO accept and persist the locale value without error

3.3 WHEN a user is assigned a non-admin role via roleId THEN the system SHALL CONTINUE TO allow the assignment and complete user creation/update successfully

3.4 WHEN a user is created with null or empty displayPreferences THEN the system SHALL CONTINUE TO persist null without error (null bypass in the converter)

3.5 WHEN a user is created or updated with valid name, email, phone, and roleId THEN the system SHALL CONTINUE TO validate email uniqueness, validate locale, resolve role, and persist the entity correctly

3.6 WHEN the user scrolls the roles list without typing a search query THEN the system SHALL CONTINUE TO display all available roles (paginated) in their natural order

3.7 WHEN the RoleSelect dropdown is closed and re-opened THEN the system SHALL CONTINUE TO reset the search state and show the initial unfiltered first page of roles
