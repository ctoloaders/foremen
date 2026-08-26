# Requirements Document

## Introduction

FOR-02-08 — Theme Settings: a settings page and supporting infrastructure for user-customizable appearance in the Foremen admin panel. Users can switch between dark, light, and system-following theme modes, select a color scheme preset, and adjust font size. Preferences are persisted per-user on the backend (`display_preferences` JSONB) when authenticated, with a localStorage fallback before login. Changes apply immediately via CSS custom properties and Tailwind dark mode class strategy.

## Glossary

- **Theme_Settings_Page** — the UI page accessible from the sidebar where users configure appearance preferences
- **Theme_Mode** — the dark/light/system appearance mode toggle; "system" follows the OS preference via `prefers-color-scheme`
- **Color_Scheme** — a named preset palette defining a set of CSS custom property values (e.g., Zinc, Slate, Stone, Gray, Neutral, Blue, Green, Orange, Red)
- **Font_Size** — a named size option applied globally: sm (12px), default (14px), lg (16px), xl (18px)
- **Display_Preferences** — the JSONB field on the User entity storing `{ themeMode, colorScheme, fontSize }`
- **Theme_Store** — the Zustand store slice managing theme state on the client (themeMode, colorScheme, fontSize)
- **Preferences_API** — the backend endpoint for reading and writing a user's `display_preferences`
- **Live_Preview** — the behavior where changing a preference immediately applies it to the UI without requiring an explicit save action

## Requirements

### Requirement 1: Theme Mode Switching

**User Story:** As a user, I want to switch between dark, light, and system-following themes, so that I can use the application in my preferred visual mode.

#### Acceptance Criteria

1. THE Theme_Settings_Page SHALL display three selectable theme mode options: "dark", "light", and "system", where only one option can be selected at a time
2. WHEN the user selects "dark", THE Theme_Store SHALL set themeMode to "dark" and THE App_Shell SHALL add the class `dark` to the `<html>` element and remove the class `light` within 100ms of selection
3. WHEN the user selects "light", THE Theme_Store SHALL set themeMode to "light" and THE App_Shell SHALL add the class `light` to the `<html>` element and remove the class `dark` within 100ms of selection
4. WHEN the user selects "system", THE Theme_Store SHALL set themeMode to "system" and THE App_Shell SHALL add the class `dark` to the `<html>` element if the OS `prefers-color-scheme` media query evaluates to "dark", or add the class `light` and remove the class `dark` if it evaluates to "light"
5. WHILE themeMode is "system" and the OS color scheme changes, THE App_Shell SHALL update the `dark`/`light` class on `<html>` within 100ms of the media query change event
6. THE Theme_Settings_Page SHALL visually indicate the currently active theme mode option by applying a distinct selected state (e.g., highlighted border or background) to the active option
7. WHEN the application loads, THE Theme_Store SHALL restore the previously selected themeMode from localStorage, and IF no previously stored value exists, THEN THE Theme_Store SHALL default themeMode to "system"
8. WHEN themeMode changes, THE Theme_Store SHALL persist the new themeMode value to localStorage within 100ms of the change event
9. IF localStorage is unavailable or read fails on application load, THEN THE Theme_Store SHALL default themeMode to "system" and THE App_Shell SHALL apply the class based on the OS `prefers-color-scheme` media query result

---

### Requirement 2: Light Theme Definition

**User Story:** As a user, I want a properly designed light theme, so that I can use the application comfortably in bright environments.

#### Acceptance Criteria

1. THE index.css SHALL define a light mode color set under `:root` (without `.dark`) containing all semantic tokens matching the existing dark theme: background, foreground, card, card-foreground, border, primary, primary-foreground, secondary, secondary-foreground, muted, muted-foreground, accent, accent-foreground, destructive, success, warning, info, input, and ring
2. THE light theme SHALL define background as a light value (lightness ≥ 95% in HSL) and foreground as a dark value (lightness ≤ 15% in HSL), such that the foreground-to-background contrast ratio is at least 15:1
3. WHILE the `dark` class is present on the `<html>` element, THE CSS SHALL apply dark theme variable values using a `.dark` selector or `html.dark` selector that overrides the light `:root` defaults
4. WHILE the `dark` class is absent from the `<html>` element, THE CSS SHALL apply light theme variable values from the `:root` selector as the default theme
5. THE light theme tokens SHALL maintain WCAG AA contrast ratio (minimum 4.5:1 for normal text sized below 18.66px bold or 24px regular, minimum 3:1 for large text at or above those sizes) between each foreground token and its corresponding background token for all token pairs: foreground/background, card-foreground/card, primary-foreground/primary, secondary-foreground/secondary, muted-foreground/muted, accent-foreground/accent
6. WHEN the light theme is active, THE CSS SHALL preserve the existing `--radius` value of 8px and all non-color tokens unchanged from the dark theme definition
7. IF any third-party component override exists in index.css (e.g., react-phone-number-input), THEN THE CSS SHALL ensure those overrides remain functional in both light and dark themes by referencing CSS custom properties rather than hardcoded color values

---

### Requirement 3: Color Scheme Presets

**User Story:** As a user, I want to choose a color scheme from predefined palettes, so that I can personalize the accent colors of the application.

#### Acceptance Criteria

1. THE Theme_Settings_Page SHALL display a grid of selectable Color_Scheme presets, each represented by a circular or rectangular color swatch with a minimum touch target size of 44×44 pixels, showing the preset's primary color
2. THE system SHALL provide a minimum of 6 color scheme presets (e.g., Zinc, Slate, Blue, Green, Orange, Red), each defining values for: primary, primary-foreground, ring, and accent CSS custom properties
3. WHEN the user selects a Color_Scheme preset, THE Theme_Store SHALL update the colorScheme value and THE App_Shell SHALL apply the corresponding CSS custom property values to the document root element within 100 milliseconds
4. Each Color_Scheme preset SHALL define both dark and light variants, and THE system SHALL apply the appropriate variant based on the current resolved theme mode (dark or light)
5. WHEN the resolved theme mode changes (e.g., from light to dark), THE system SHALL re-apply the currently selected Color_Scheme preset using the variant matching the new mode without requiring user re-selection
6. THE Theme_Settings_Page SHALL visually indicate the currently selected Color_Scheme preset with a check mark overlay or a distinct border highlight that meets WCAG 2.1 AA contrast requirements (minimum 3:1 contrast ratio against adjacent colors)
7. THE default Color_Scheme SHALL be "zinc" (matching the current hardcoded dark theme values), and IF no previously saved colorScheme value exists in the Theme_Store, THEN THE system SHALL apply "zinc" as the active Color_Scheme on initial load
8. IF the user selects the already-active Color_Scheme preset, THEN THE system SHALL retain the current selection and not trigger a re-application of CSS custom properties

---

### Requirement 4: Font Size Selection

**User Story:** As a user, I want to adjust the application font size, so that I can improve readability according to my preference.

#### Acceptance Criteria

1. THE Theme_Settings_Page SHALL display four selectable font size options: sm (12px), default (14px), lg (16px), xl (18px)
2. WHEN the user selects a font size, THE Theme_Store SHALL update the fontSize value and THE App_Shell SHALL set the CSS custom property `--font-size-base` on `<html>` to the corresponding pixel value
3. THE body element SHALL use `var(--font-size-base)` as its base font-size so that all elements sized with relative units (rem, em, %) scale proportionally to the selected base value
4. THE Theme_Settings_Page SHALL visually distinguish the currently selected font size option from unselected options (e.g., highlighted border, background, or check mark)
5. THE default font size SHALL be "default" (14px), matching the current hardcoded body font-size
6. IF the Theme_Store receives a fontSize value that is not one of "sm", "default", "lg", or "xl" (e.g., corrupted localStorage data), THEN THE Theme_Store SHALL fall back to "default" (14px) without throwing an error

---

### Requirement 5: Live Preview

**User Story:** As a user, I want to see appearance changes applied immediately, so that I can evaluate them before they are persisted.

#### Acceptance Criteria

1. WHEN the user changes any preference (themeMode, colorScheme, fontSize), THE App_Shell SHALL apply the change to the document's CSS custom properties and dark/light class within 100ms of the user interaction, without waiting for a backend save response
2. THE Theme_Settings_Page SHALL display a "Save" button that triggers persistence of the current Theme_Store state to the backend via PATCH request, and on success SHALL update the savedPreferences reference in Theme_Store to match the current preferences
3. THE Theme_Settings_Page SHALL display a "Reset" button that reverts all preferences (themeMode, colorScheme, fontSize) in Theme_Store to the last saved state (savedPreferences), or to system defaults (themeMode: "system", colorScheme: "zinc", fontSize: "default") if nothing was previously saved
4. WHILE unsaved changes exist (any value in Theme_Store current preferences differs from savedPreferences), THE Theme_Settings_Page SHALL visually indicate unsaved state by enabling the Save button and displaying an unsaved-changes indicator
5. IF the user navigates away from the Theme_Settings_Page with unsaved changes, THEN THE App_Shell SHALL revert the live preview by restoring CSS custom properties and dark/light class to match the savedPreferences values
6. IF the Save request to the backend fails, THEN THE Theme_Settings_Page SHALL display an error message indicating the save failed, retain the current unsaved preferences in Theme_Store without reverting the live preview, and keep the Save button enabled for retry
7. IF the backend is unreachable during save, THEN THE Theme_Settings_Page SHALL persist the current preferences to localStorage as fallback, display a message indicating offline save, and update savedPreferences to reflect the locally persisted state

---

### Requirement 6: Backend Persistence

**User Story:** As a user, I want my appearance preferences saved on the server, so that they follow me across devices and browser sessions.

#### Acceptance Criteria

1. WHEN the user clicks "Save" on the Theme_Settings_Page, THE system SHALL send a PATCH request to the Preferences_API with the payload `{ themeMode, colorScheme, fontSize }` within 1 second of the click event
2. THE Preferences_API SHALL accept PATCH requests at `PATCH /api/users/{id}/display-preferences` with a JSON body containing themeMode (string), colorScheme (string), and fontSize (string) fields, where the request body size does not exceed 1 KB
3. THE Preferences_API SHALL validate that themeMode is one of "dark", "light", "system"; colorScheme is one of the defined preset names; fontSize is one of "sm", "default", "lg", "xl"
4. IF validation fails, THEN THE Preferences_API SHALL return HTTP 400 with an error message indicating which fields failed validation and the allowed values for each invalid field
5. IF the requesting user's ID does not match the `{id}` path parameter, THEN THE Preferences_API SHALL return HTTP 403 and not modify the target user's preferences
6. WHEN the save succeeds, THE Theme_Settings_Page SHALL display a success toast notification that auto-dismisses after 5 seconds
7. IF the save fails due to a network error or a server error (HTTP 5xx), THEN THE Theme_Settings_Page SHALL display an error toast notification indicating the failure reason and retain the unsaved changes in the Theme_Store so the user can retry without re-entering values
8. WHILE the PATCH request is in-flight, THE Theme_Settings_Page SHALL disable the "Save" button and display a loading indicator to prevent duplicate submissions

---

### Requirement 7: localStorage Fallback (Pre-Login)

**User Story:** As an unauthenticated user, I want my appearance preferences remembered locally, so that the application looks correct before I log in.

#### Acceptance Criteria

1. WHILE no authenticated user session exists, THE Theme_Store SHALL persist preferences to localStorage under the key `foremen-theme-preferences` as a JSON object containing themeMode, colorScheme, and fontSize fields within 100ms of any preference change
2. WHILE no authenticated user session exists, THE Theme_Store SHALL read preferences from localStorage key `foremen-theme-preferences` on application startup and apply them before the first meaningful paint
3. IF the stored JSON in localStorage is malformed or contains invalid values (themeMode not in ["light", "dark", "system"], colorScheme not in the defined palette list, fontSize not in ["sm", "default", "lg", "xl"]), THEN THE Theme_Store SHALL discard the invalid entry, use default values (themeMode: "system", colorScheme: "zinc", fontSize: "default"), and overwrite localStorage with the defaults
4. WHEN a user session becomes available (after login), THE system SHALL merge localStorage preferences with backend preferences, where backend values take precedence for any field that exists and is non-null on the backend, and localStorage values are used for fields not present on the backend
5. IF localStorage is unavailable (e.g., private browsing restrictions, storage quota exceeded, or SecurityError), THEN THE Theme_Store SHALL use default values (themeMode: "system", colorScheme: "zinc", fontSize: "default") and operate in memory-only mode without throwing errors or degrading other functionality
6. WHEN a preference value changes while no authenticated user session exists, THE Theme_Store SHALL write the complete preferences object to localStorage atomically (full overwrite, not partial field update) so that no partial state is persisted

---

### Requirement 8: Preferences Loading on Startup

**User Story:** As a returning user, I want my saved appearance to load immediately when I open the application, so that there is no flash of unstyled content.

#### Acceptance Criteria

1. WHEN the application starts and a user session exists, THE system SHALL fetch display_preferences from the backend via `GET /api/users/{id}/display-preferences` within 3 seconds of session detection
2. WHEN the preferences are fetched successfully, THE Theme_Store SHALL update with the fetched values (theme mode and color scheme) and THE App_Shell SHALL apply them to the document root element within the same render cycle
3. WHILE preferences are being fetched from the backend, THE system SHALL apply localStorage-cached preferences (theme mode and color scheme) to the document root element to prevent a flash of default theme
4. IF the backend fetch fails (network error, timeout after 3 seconds, or non-2xx response), THEN THE system SHALL continue using localStorage-cached preferences for the remainder of the session and SHALL NOT display an error to the user
5. THE system SHALL apply theme mode and color scheme during the initial render cycle via a synchronous blocking inline script in index.html that reads localStorage and applies CSS classes/custom properties to the document element before React hydrates
6. IF no localStorage-cached preferences exist and no user session is detected, THEN THE system SHALL apply the system default theme (themeMode: "system", colorScheme: "zinc", fontSize: "default") without delay
7. WHEN the backend fetch returns preferences that differ from the localStorage-cached values, THE system SHALL update both the Theme_Store and localStorage with the backend values and apply them to the document within the current render cycle

---

### Requirement 9: CSS Custom Properties Application

**User Story:** As a developer, I want theme changes applied via CSS custom properties, so that all components inherit the correct colors without individual re-rendering.

#### Acceptance Criteria

1. THE system SHALL apply color scheme values by setting CSS custom properties on the `<html>` element using hex format color values: --background, --foreground, --primary, --primary-foreground, --border, --secondary, --muted, --muted-foreground, --accent, --accent-foreground, --ring, --input, --card, --card-foreground, --destructive, --success, --warning, --info
2. THE system SHALL apply font size by setting `--font-size-base` on the `<html>` element, accepting values in the range of 12px to 18px inclusive
3. WHEN the user toggles dark mode, THE system SHALL add or remove the Tailwind `dark` class on the `<html>` element, and SHALL update the structural CSS custom properties (--background, --foreground, --card, --card-foreground, --border, --secondary, --muted, --muted-foreground) to the corresponding dark or light values while preserving color scheme properties (--primary, --accent, --ring) unchanged
4. WHEN CSS custom properties on the `<html>` element are updated, all components using `var(--*)` references SHALL reflect the new values within the same rendering frame without triggering a React component re-render
5. IF a color scheme preset is applied, THEN THE system SHALL override only the color scheme properties (--primary, --primary-foreground, --accent, --accent-foreground, --ring) on the `<html>` element while retaining the current structural property values set by the active dark/light mode

---

### Requirement 10: Settings Page Navigation and Layout

**User Story:** As a user, I want the theme settings page accessible from the main navigation, so that I can find and modify my preferences easily.

#### Acceptance Criteria

1. THE navigation configuration SHALL include a Nav_Item for the Theme_Settings_Page with route `/settings/appearance`, Lucide icon `Palette`, i18n key `nav.settings.appearance`, and `bottomNav` flag set to `false`
2. THE navigation configuration SHALL include a Nav_Section with i18n key `nav.sections.settings` containing the Theme_Settings_Page Nav_Item, and THE Sidebar SHALL display this section after the existing "System" section
3. WHILE viewport width is less than 768px, THE Theme_Settings_Page SHALL render controls in a single-column layout with all interactive elements having a minimum touch target of 44×44 CSS pixels
4. WHILE viewport width is in the range 768px–1023px, THE Theme_Settings_Page SHALL render controls in a single-column layout with a maximum content width of 640px centered in the Main_Content area
5. WHILE viewport width is 1024px or greater, THE Theme_Settings_Page SHALL render controls in a two-column layout (control labels on the left, selectors on the right) with a maximum content width of 960px
6. THE Theme_Settings_Page SHALL group controls into three sections in this order: Theme Mode, Color Scheme, Font Size — each section with a heading rendered via i18n key `settings.appearance.<sectionId>.title` and a description rendered via i18n key `settings.appearance.<sectionId>.description`
7. THE Router SHALL register route `/settings/appearance` with lazy-loading of the Theme_Settings_Page component and a Skeleton_Page fallback during loading

---

### Requirement 11: Internationalization

**User Story:** As a user, I want all theme settings labels in my chosen language, so that I can understand the controls.

#### Acceptance Criteria

1. THE Theme_Settings_Page SHALL render all labels, headings, descriptions, and button text via i18next keys using the "settings" namespace, with zero hardcoded user-visible strings in the component templates
2. THE system SHALL provide translations for both supported locales ("pl" and "ru"), covering all keys used by the Theme_Settings_Page such that every key referenced in the component exists in both pl.json and ru.json under the "settings" namespace
3. WHEN the user changes the application locale, THE Theme_Settings_Page SHALL update all visible text to the newly selected locale within 500ms without triggering a full page reload or losing current unsaved form state
4. WHEN a theme save operation succeeds, THE toast notification SHALL display an i18next-translated success message from the "settings" namespace, and WHEN a theme save operation fails, THE toast notification SHALL display an i18next-translated failure message from the "settings" namespace
5. IF an i18next key is missing in the current locale, THEN THE system SHALL fall back to the "pl" locale value for that key and render the Polish translation in place of the missing key
6. IF the fallback "pl" locale also lacks the requested key, THEN THE system SHALL render the raw i18next key string as visible text rather than displaying an empty string or throwing a rendering error

---

*Created: August 2026*
