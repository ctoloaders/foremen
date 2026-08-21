# Requirements Document

## Introduction

FOR-02-02 — App Shell: the SPA application shell for Foremen. Includes sidebar navigation, top bar, adaptive responsive layout (mobile-first), routing structure, and placeholder pages with skeleton loading. Serves as the frame into which subsequent specs will integrate functional pages.

## Glossary

- **App_Shell** — the root layout component of the application, containing the Sidebar, Top_Bar, and main content area (Main_Content)
- **Sidebar** — the side navigation panel with a width of 240px (desktop), containing branding, navigation items, and user information
- **Top_Bar** — the top page panel with the current page title, language switcher, and action button
- **Bottom_Nav** — the bottom navigation bar for mobile devices (< 768px) with 5 icons for main sections
- **Drawer** — a slide-out side panel for mobile devices containing the full navigation menu
- **Collapsed_Sidebar** — the collapsed side panel (icons only) for tablets (768–1024px)
- **Skeleton_Page** — a placeholder page with shimmer animation displaying skeleton loading of future content
- **Nav_Item** — a navigation element: icon + text label + route path
- **Nav_Section** — a named group of navigation items (e.g., «Magazyn», «System»)
- **Router** — the routing system based on React Router v7, providing lazy-loading of pages

## Requirements

### Requirement 1: Root Layout (App Shell)

**User Story:** As a user, I want to see a unified application shell with navigation and a content area, so that I can navigate between sections without page reloads.

#### Acceptance Criteria

1. THE App_Shell SHALL render the Sidebar with a width of 240px (fixed, not scrolling with content), the Top_Bar, and Main_Content as a single layout wrapping all child routes of the application
2. THE App_Shell SHALL use a dark theme with background #09090b and Inter font
3. THE App_Shell SHALL occupy 100% of viewport height (min-height: 100vh)
4. THE Main_Content SHALL be positioned to the right of the Sidebar with 24px padding, and the Top_Bar SHALL be displayed above Main_Content between the top edge of the viewport and the content area with a bottom border (border-bottom) colored var(--border)
5. WHEN the user selects a navigation item in the Sidebar, THE App_Shell SHALL render the corresponding route in the Main_Content area without a full page reload (client-side routing)
6. WHEN viewport width is less than 768px, THE App_Shell SHALL hide the Sidebar, and Main_Content SHALL occupy the full viewport width

---

### Requirement 2: Sidebar (Desktop)

**User Story:** As a desktop user, I want to see a fixed side panel with navigation, so that I can quickly switch between sections.

#### Acceptance Criteria

1. WHILE viewport width exceeds 1024px, THE Sidebar SHALL be displayed fixed on the left with a width of 240px
2. THE Sidebar SHALL contain a header with a brand icon «F» (white 28×28 square with 6px border-radius) and the text «Foremen»
3. THE Sidebar SHALL display navigation items grouped into sections: an upper group without a title (Dashboard, Projekty, Pomieszczenia, Kosztorys), a «Magazyn» section (Materiały, Finanse, Dostawy), a «System» section (Użytkownicy)
4. THE Sidebar SHALL display a footer with a user avatar (initials — first letters of first and last name), name, and role (placeholder data until authorization is implemented)
5. WHEN the current page matches a Nav_Item route, THE Sidebar SHALL visually highlight that Nav_Item with a background of var(--secondary) and color var(--foreground), with only one Nav_Item highlighted as active at a time
6. THE Sidebar SHALL have a right border (border-right) colored var(--border)

---

### Requirement 3: Responsiveness — Tablet (Collapsed Sidebar)

**User Story:** As a tablet user, I want to see compact navigation, so that screen space is used efficiently.

#### Acceptance Criteria

1. WHILE viewport width is in the range 768–1024px, THE Sidebar SHALL be displayed in a collapsed form with a width of 64px, showing only navigation item icons without text labels
2. WHEN the user hovers over the Collapsed_Sidebar, THE Sidebar SHALL expand to full width of 240px as an overlay on top of the main content with an animation duration of no more than 300ms
3. WHEN the user moves the cursor away from the expanded Sidebar, THE Sidebar SHALL return to the collapsed form (64px) with an animation duration of no more than 300ms
4. WHEN the user on a touch device taps on the Collapsed_Sidebar, THE Sidebar SHALL expand to full width of 240px as an overlay on top of the main content, and SHALL return to the collapsed form when tapping outside the Sidebar
5. WHILE the Sidebar is displayed in the expanded (overlay) form on a tablet, THE System SHALL dim the main content with a semi-transparent background and SHALL NOT shift the main content
6. IF viewport width becomes greater than 1024px, THEN THE Sidebar SHALL be displayed in full form (240px) with text labels without requiring hover interaction

---

### Requirement 4: Responsiveness — Mobile (Bottom Nav + Drawer)

**User Story:** As a mobile user, I want touch-optimized navigation, so that I can comfortably use the application with one hand.

#### Acceptance Criteria

1. WHILE viewport width is less than 768px, THE Sidebar SHALL be hidden and not rendered
2. WHILE viewport width is less than 768px, THE App_Shell SHALL display a Bottom_Nav with 5 icons: Dashboard, Projekty, Materiały, Finanse, Użytkownicy, where each item has a minimum touch target of 44×44 CSS pixels
3. WHEN the user taps an icon in the Bottom_Nav, THE Router SHALL navigate to the corresponding route
4. THE Bottom_Nav SHALL highlight the active item with color var(--foreground), while inactive items are displayed with color var(--muted-foreground)
5. WHILE viewport width is less than 768px, THE Top_Bar SHALL contain a hamburger menu button (hamburger menu icon)
6. WHEN the user taps the hamburger button, THE Drawer SHALL be displayed as a slide-out panel from the left with the full navigation menu (all sections and items from the configuration)
7. WHEN the user taps on the overlay outside the Drawer or on the close button, THE Drawer SHALL close
8. WHILE viewport width is less than 768px, THE Main_Content SHALL use a single-column layout with 100% container width
9. WHILE the user scrolls Main_Content on a viewport less than 768px, THE Bottom_Nav SHALL remain fixed at the bottom of the screen (position: fixed)

---

### Requirement 5: Top Bar

**User Story:** As a user, I want to see a top bar with the current page title and quick actions, so that I can orient myself in the application.

#### Acceptance Criteria

1. THE Top_Bar SHALL display the current page title on the left side of the panel, where the title text is truncated with an ellipsis when it exceeds the available width
2. THE Top_Bar SHALL display a language switch button on the right, showing the current active locale as a text label «PL» or «RU»
3. WHEN the user clicks the language button, THE App_Shell SHALL switch the application locale to the opposite (from «pl» to «ru» or from «ru» to «pl»), and all text UI elements SHALL update without a page reload
4. THE Top_Bar SHALL have a bottom border (border-bottom) colored var(--border)
5. WHEN the current page defines a primary action (e.g., creating a new object), THE Top_Bar SHALL display that action's button on the right with text corresponding to the current locale
6. IF the current page does not define a primary action, THEN THE Top_Bar SHALL not display an action button, leaving only the title and language switcher

---

### Requirement 6: Routing Structure

**User Story:** As a developer, I want to have a predefined route structure with lazy-loading, so that I can add pages in future specs without refactoring the router.

#### Acceptance Criteria

1. THE Router SHALL define the following routes: / (Dashboard), /projects, /rooms, /estimate, /materials, /finances, /deliveries, /users
2. THE Router SHALL use React.lazy for loading page components
3. THE Router SHALL wrap lazy components in React.Suspense with a Skeleton_Page fallback component
4. THE Router SHALL use App_Shell as the root layout element for all routes, including the 404 page
5. IF the user navigates to a route that does not match any of those defined in criterion 1, THEN THE Router SHALL display a «404 — Strona nie znaleziona» page with a navigation link to return to the Dashboard (/)
6. IF loading of a lazy page component fails (network failure or missing chunk file), THEN THE Router SHALL display a loading error message with the ability to retry without reloading the entire page

---

### Requirement 7: Placeholder Pages (Skeleton Loading)

**User Story:** As a user, I want to see skeleton loading when navigating between sections, so that I understand that content is loading.

#### Acceptance Criteria

1. THE Skeleton_Page SHALL display a page title (text placeholder with 40–60% container width) and a subtitle description (text placeholder with 60–80% container width)
2. THE Skeleton_Page SHALL display 3 to 6 shimmer-animated placeholder cards, each containing rectangular placeholder blocks of fixed height simulating the structure of future content
3. THE Skeleton_Page SHALL use a CSS pulse animation with a cycle duration of 1.5–2 seconds to create a loading effect
4. WHEN the lazy page component is loaded, THE Skeleton_Page SHALL be replaced with the actual page content without a reload
5. WHILE viewport width is less than 768px, THE Skeleton_Page SHALL display cards in a single column; WHILE viewport width is 1024px or greater, THE Skeleton_Page SHALL display cards in a 2–3 column grid
6. THE Skeleton_Page SHALL be used as the React Suspense fallback component for each lazy-loaded route

---

### Requirement 8: Navigation Configuration

**User Story:** As a developer, I want to have a centralized navigation configuration, so that I can add and modify menu items in a single place.

#### Acceptance Criteria

1. THE App_Shell SHALL load the navigation structure from a single configuration object (an array of Nav_Item and Nav_Section)
2. THE Nav_Item SHALL contain: route path, i18n key for the label, Lucide icon identifier, and a flag for display in Bottom_Nav (boolean)
3. THE Nav_Section SHALL contain: an i18n key for the section title and an ordered array of Nav_Item, where the element order determines the display order
4. WHEN the navigation configuration changes (addition, removal, or reordering of items), THE Sidebar, Bottom_Nav, and Drawer SHALL reflect the changes without modifying component source code
5. WHEN the current route matches a Nav_Item path, THE App_Shell SHALL display the corresponding Nav_Item in an active (highlighted) state
6. IF a Nav_Item references a Lucide icon identifier that does not exist in the library, THEN THE App_Shell SHALL display a default placeholder icon

---

### Requirement 9: Navigation Internationalization

**User Story:** As a user, I want to see the interface in my chosen language (PL or RU), so that I can work in a comfortable language environment.

#### Acceptance Criteria

1. THE App_Shell SHALL render all navigation text labels via i18next keys (namespace «nav»)
2. WHEN the user selects a different locale via the language switcher, THE App_Shell SHALL update all navigation text labels and page titles without a page reload
3. THE App_Shell SHALL support two locales: «pl» (default) and «ru», and SHALL persist the selected locale in localStorage for restoration on the next visit
4. THE Skeleton_Page SHALL render titles and descriptions via i18next keys (namespace «pages»)
5. IF an i18next key is missing in the translation file for the current locale, THEN THE App_Shell SHALL display the key value from the «pl» locale as a fallback

---

### Requirement 10: Sidebar State Management

**User Story:** As a user, I want to control sidebar visibility, so that I can expand the content area when needed.

#### Acceptance Criteria

1. THE App_Shell SHALL use useUIStore.sidebarOpen to manage the Sidebar visibility state, with the initial value of sidebarOpen equal to true
2. WHEN useUIStore.toggleSidebar is called, THE Sidebar SHALL toggle visibility with a CSS transition of 200ms duration
3. WHILE sidebarOpen is true on a viewport width ≥1024px, THE Sidebar SHALL be displayed fixed with a width of 240px, and Main_Content SHALL have a left offset of 240px
4. WHILE sidebarOpen is false on a viewport width ≥1024px, THE Main_Content SHALL occupy 100% of viewport width without a left offset
5. WHILE viewport width is <768px, THE App_Shell SHALL manage the Drawer via useUIStore.sidebarOpen
6. WHEN useUIStore.toggleSidebar is called on a viewport width <768px, THE Drawer SHALL open over the content with a dimmed background (backdrop)
