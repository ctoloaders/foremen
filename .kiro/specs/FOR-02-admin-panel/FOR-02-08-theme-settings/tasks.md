# Implementation Plan: Theme Settings (FOR-02-08-theme-settings)

## Overview

Implement a settings page and supporting infrastructure for user-customizable appearance in the Foremen admin panel. The feature introduces theme mode switching (dark/light/system), color scheme presets, font size selection, backend persistence via the existing `displayPreferences` JSONB field, and flash-free startup via an inline blocking script. Tech stack: React 19, TypeScript 5, Zustand, TanStack Query v5, Tailwind CSS v4, i18next (PL/RU), Vitest + fast-check (frontend), Spring Boot + JPA + JUnit 5 + jqwik (backend).

## Tasks

- [x] 1. CSS architecture and FOUC prevention
  - [x] 1.1 Refactor index.css to support light/dark themes with CSS custom properties
    - Add `@custom-variant dark (&:where(.dark, .dark *));` directive for Tailwind v4 class-based dark mode
    - Refactor `@theme` block to reference CSS custom properties via `var(--*)` instead of hardcoded hex values
    - Define `:root` with light theme color tokens (background, foreground, card, card-foreground, border, primary, primary-foreground, secondary, secondary-foreground, muted, muted-foreground, accent, accent-foreground, destructive, success, warning, info, input, ring)
    - Define `.dark` selector with dark theme color tokens (matching current hardcoded dark values)
    - Add `--font-size-base: 14px` custom property to `:root`
    - Update `body` font-size to use `var(--font-size-base)`
    - Ensure existing phone-input overrides remain functional via CSS custom property references
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 9.1, 9.2_

  - [x] 1.2 Add inline theme script to index.html for FOUC prevention
    - Add synchronous `<script>` block in `<head>` before any CSS/JS loads
    - Script reads `foremen-theme-preferences` from localStorage
    - Script resolves theme mode: "dark" → add `.dark` class; "light" → no class; "system" → check `prefers-color-scheme` media query
    - Script applies `--font-size-base` CSS property if fontSize is stored
    - Script uses try/catch for localStorage unavailability
    - _Requirements: 8.5, 8.6, 1.7_

- [x] 2. Theme store and pure utility functions
  - [x] 2.1 Create theme types and constants
    - Create `src/features/settings/types/index.ts` with types: `ThemeMode`, `ResolvedMode`, `ColorScheme`, `FontSize`, `ThemePreferences`, `DisplayPreferencesResponse`, `DisplayPreferencesRequest`
    - Create `src/lib/theme-presets.ts` with `ColorPreset` interface, `COLOR_PRESETS` array (zinc, slate, stone, gray, neutral, blue, green, orange, red), and `FONT_SIZE_MAP` record
    - Each preset defines `dark` and `light` variants with hex values for primary, primaryForeground, accent, accentForeground, ring
    - _Requirements: 3.1, 3.2, 3.4, 4.1_

  - [x] 2.2 Implement theme applicator utility functions
    - Create `src/lib/theme-applicator.ts` with pure functions:
      - `applyThemeMode(mode: ThemeMode, resolvedMode: ResolvedMode)` — adds/removes `dark` class on `document.documentElement`
      - `applyColorScheme(scheme: ColorScheme, resolvedMode: ResolvedMode)` — sets --primary, --primary-foreground, --accent, --accent-foreground, --ring from preset values
      - `applyFontSize(size: FontSize)` — sets --font-size-base on document element
      - `resolveMode(mode: ThemeMode): ResolvedMode` — resolves "system" using `matchMedia`
      - `validatePreferences(raw: unknown): ThemePreferences` — validates/sanitizes any input to valid defaults
      - `mergePreferences(local: ThemePreferences, backend: DisplayPreferencesResponse): ThemePreferences` — backend non-null wins
    - _Requirements: 1.2, 1.3, 1.4, 3.3, 4.2, 7.3, 7.4, 9.1, 9.2, 9.3, 9.5_

  - [x] 2.3 Create theme Zustand store
    - Create `src/stores/theme-store.ts` with state: themeMode, colorScheme, fontSize, resolvedMode, savedPreferences
    - Implement actions: setThemeMode, setColorScheme, setFontSize, updateSavedPreferences, revertToSaved, hasUnsavedChanges
    - Initialize from localStorage (`foremen-theme-preferences` key) with validation/fallback
    - Persist changes to localStorage on every state update
    - Set up `matchMedia('(prefers-color-scheme: dark)')` listener for system mode changes
    - _Requirements: 1.7, 1.8, 1.9, 5.3, 5.4, 7.1, 7.2, 7.5, 7.6_

  - [x] 2.4 Write property tests for theme applicator (Properties 1, 5, 6, 7)
    - **Property 1: Theme mode resolves to correct HTML class**
    - **Property 5: Color scheme application sets correct CSS properties**
    - **Property 6: Mode toggle preserves color scheme properties**
    - **Property 7: Font size maps to correct pixel value**
    - Create `src/lib/__tests__/theme-applicator.property.test.ts`
    - Use fast-check with minimum 100 iterations per property
    - **Validates: Requirements 1.2, 1.3, 1.4, 2.3, 2.4, 3.3, 3.5, 4.2, 9.1, 9.2, 9.3, 9.5**

  - [x] 2.5 Write property tests for theme presets (Property 4)
    - **Property 4: Color preset structural completeness**
    - Create `src/lib/__tests__/theme-presets.property.test.ts`
    - Verify every preset has dark/light variants with non-empty hex strings for all required fields
    - Use fast-check with minimum 100 iterations
    - **Validates: Requirements 3.2, 3.4**

  - [x] 2.6 Write property tests for validation, round-trip, merge (Properties 2, 3, 9, 10)
    - **Property 2: Preferences validation and fallback**
    - **Property 3: localStorage preferences round-trip**
    - **Property 9: Unsaved changes detection**
    - **Property 10: Backend merge with backend-takes-precedence**
    - Create `src/lib/__tests__/theme-validation.property.test.ts`
    - Use fast-check with minimum 100 iterations per property
    - **Validates: Requirements 1.7, 4.6, 7.1, 7.2, 7.3, 7.4, 7.6, 5.4, 8.7**

  - [x] 2.7 Write property tests for theme store (Properties 8, 9)
    - **Property 8: Revert restores saved preferences**
    - **Property 9: Unsaved changes detection (store-level)**
    - Create `src/stores/__tests__/theme-store.property.test.ts`
    - Use fast-check with minimum 100 iterations per property
    - **Validates: Requirements 5.3, 5.4**

- [x] 3. Checkpoint - Verify theme utilities compile and property tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Backend display preferences endpoint
  - [x] 4.1 Create DisplayPreferencesRequest DTO and DisplayPreferencesController
    - Create `DisplayPreferencesRequest` record in `com.foremen.controller.model` with `@NotNull @Pattern` validation for themeMode, colorScheme, fontSize
    - Create `DisplayPreferencesController` at `/api/users/{id}/display-preferences`
    - Implement `GET /{id}/display-preferences` — returns stored map or empty object
    - Implement `PATCH /{id}/display-preferences` — validates body, stores to `displayPreferences` JSONB, returns saved map
    - Add authorization check: requesting user ID must match `{id}` path parameter (HTTP 403 otherwise)
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

  - [x] 4.2 Write backend tests for DisplayPreferencesController
    - **Property 11: Backend validation rejects invalid preference values**
    - Test valid PATCH → 200 with correct stored values
    - Test invalid themeMode → 400
    - Test invalid colorScheme → 400
    - Test invalid fontSize → 400
    - Test mismatched user ID → 403
    - Test GET with null displayPreferences → empty object
    - Use JUnit 5 for example tests and jqwik for property test (random invalid strings)
    - **Validates: Requirements 6.3, 6.4, 6.5**

- [x] 5. Frontend API layer and AppShell integration
  - [x] 5.1 Implement preferences API client and query/mutation hooks
    - Create `src/features/settings/api/preferences-api.ts` with `fetchDisplayPreferences(userId)` and `patchDisplayPreferences(userId, body)` functions
    - Create `src/features/settings/api/query-hooks.ts` with `useDisplayPreferences(userId)` hook (staleTime 30s)
    - Create `src/features/settings/api/mutation-hooks.ts` with `useSavePreferences(userId)` mutation hook — invalidates preferences query on success
    - Include `Accept-Language` header from stored locale
    - _Requirements: 6.1, 6.2, 8.1, 8.4_

  - [x] 5.2 Integrate theme store into AppShell
    - Add `useEffect` in AppShell (or a dedicated `useThemeApplicator` hook) that subscribes to theme store changes
    - On theme store change: call `applyThemeMode`, `applyColorScheme`, `applyFontSize`
    - On app startup (when user session exists): fetch display preferences, merge with localStorage (backend wins), update theme store
    - On navigate away from settings page: revert to savedPreferences if unsaved changes exist
    - _Requirements: 1.2, 1.3, 1.4, 1.5, 3.3, 3.5, 4.2, 5.5, 8.1, 8.2, 8.3, 8.7, 9.3, 9.4_

- [x] 6. Settings page UI components
  - [x] 6.1 Implement ThemeModeSelector component
    - Create `src/features/settings/components/ThemeModeSelector.tsx`
    - Render three toggle options: Dark (Moon icon), Light (Sun icon), System (Monitor icon)
    - Highlight active option with distinct border/background
    - On selection change: call `useThemeStore.setThemeMode(mode)`
    - All labels via i18next keys
    - _Requirements: 1.1, 1.6, 11.1_

  - [x] 6.2 Implement ColorSchemeSelector component
    - Create `src/features/settings/components/ColorSchemeSelector.tsx`
    - Render grid of color swatches (min 44×44px touch target) from `COLOR_PRESETS`
    - Show check mark overlay on active swatch
    - On selection: call `useThemeStore.setColorScheme(scheme)`
    - All labels via i18next keys
    - _Requirements: 3.1, 3.6, 3.7, 3.8, 11.1_

  - [x] 6.3 Implement FontSizeSelector component
    - Create `src/features/settings/components/FontSizeSelector.tsx`
    - Render four options: sm (12px), default (14px), lg (16px), xl (18px)
    - Highlight active option
    - On selection: call `useThemeStore.setFontSize(size)`
    - All labels via i18next keys
    - _Requirements: 4.1, 4.4, 4.5, 11.1_

  - [x] 6.4 Implement SettingsAppearancePage with Save/Reset
    - Create `src/features/settings/SettingsAppearancePage.tsx`
    - Compose ThemeModeSelector, ColorSchemeSelector, FontSizeSelector in three grouped sections
    - Section headings and descriptions via i18next keys
    - Save button: triggers PATCH via `useSavePreferences`, shows loading state, disabled when no unsaved changes
    - Reset button: calls `revertToSaved()` on theme store
    - Success/error toasts (sonner) with i18n messages
    - Responsive layout: single column <768px, single column centered (640px max) 768–1023px, two-column (960px max) ≥1024px
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.6, 5.7, 6.6, 6.7, 6.8, 10.3, 10.4, 10.5, 10.6, 11.1, 11.4_

  - [x] 6.5 Register route and navigation entry
    - Add lazy-loaded route `settings/appearance` to `src/app/router.tsx`
    - Add settings navigation section to `src/config/navigation.ts` with icon `palette`, label key `nav.settings.appearance`, section title key `nav.sections.settings`
    - Create `src/features/settings/index.ts` barrel export
    - _Requirements: 10.1, 10.2, 10.7_

- [x] 7. Internationalization
  - [x] 7.1 Add i18n keys to pl.json and ru.json
    - Add keys under "settings.appearance" namespace: title, description, themeMode section (title, description, dark, light, system), colorScheme section (title, description, preset names), fontSize section (title, description, sm, default, lg, xl), save button, reset button, success toast, error toast
    - Add nav keys: `nav.sections.settings`, `nav.settings.appearance`
    - _Requirements: 11.1, 11.2, 11.4, 11.5, 11.6_

- [x] 8. Checkpoint - Full integration verification
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. Component and integration tests
  - [x] 9.1 Write unit tests for SettingsAppearancePage
    - Create `src/features/settings/__tests__/SettingsAppearancePage.test.tsx`
    - Test: renders three sections (Theme Mode, Color Scheme, Font Size)
    - Test: Save button disabled when no unsaved changes
    - Test: Save button shows loading during PATCH
    - Test: Reset button reverts to saved state
    - Test: Success toast on save success
    - Test: Error toast on save failure
    - _Requirements: 5.1, 5.2, 5.3, 5.6, 6.6, 6.7, 6.8_

  - [x] 9.2 Write unit tests for selector components
    - Create `src/features/settings/__tests__/ThemeModeSelector.test.tsx` — renders 3 options, highlights active
    - Create `src/features/settings/__tests__/ColorSchemeSelector.test.tsx` — renders all presets, shows check on active
    - Create `src/features/settings/__tests__/FontSizeSelector.test.tsx` — renders 4 options, highlights active
    - _Requirements: 1.1, 1.6, 3.1, 3.6, 4.1, 4.4_

- [x] 10. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- The inline script (task 1.2) is critical for FOUC prevention and must run before React mounts
- The `displayPreferences` JSONB field already exists on `UserEntity` — no DB migration needed
- The theme store is intentionally separate from `ui-store.ts` to allow earlier initialization

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "2.1"] },
    { "id": 1, "tasks": ["2.2", "4.1"] },
    { "id": 2, "tasks": ["2.3", "2.4", "2.5", "4.2"] },
    { "id": 3, "tasks": ["2.6", "2.7", "5.1"] },
    { "id": 4, "tasks": ["5.2", "6.1", "6.2", "6.3", "7.1"] },
    { "id": 5, "tasks": ["6.4", "6.5"] },
    { "id": 6, "tasks": ["9.1", "9.2"] }
  ]
}
```
