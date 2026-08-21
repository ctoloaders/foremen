# Requirements Document

## Introduction

This spec defines the requirements for initializing the `foremen-frontend/` project — a Vite 6 + React 19 + TypeScript 5 single-page application that serves as the foundation for the entire Foremen platform UI. The setup includes build tooling, component library, state management, routing, internationalization, testing infrastructure, design tokens derived from the existing mockup, and a well-defined project folder structure.

## Glossary

- **Frontend_Project**: The `foremen-frontend/` directory at the workspace root containing all frontend source code, configuration, and assets
- **Vite_Dev_Server**: The Vite 6 development server that serves the frontend application locally on port 3000 with hot module replacement
- **Design_Token_System**: CSS custom properties (variables) defining the visual theme of the application (colors, radii, fonts)
- **Path_Alias**: A TypeScript path mapping that allows imports using `@/` prefix to resolve to the `src/` directory
- **API_Proxy**: A Vite development proxy that forwards requests from `/api` to the backend at `http://localhost:8080`
- **Translation_File**: JSON files (`pl.json`, `ru.json`) containing localized strings used by i18next for multilingual UI support
- **Component_Library**: The shadcn/ui component collection (New York style) built on Radix primitives, installed into `src/components/ui/`
- **Test_Runner**: Vitest configured for unit and component testing with Testing Library integration

## Requirements

### Requirement 1: Project Initialization

**User Story:** As a developer, I want the frontend project initialized with Vite 6, React 19, and TypeScript 5 so that I have a modern, fast build foundation.

#### Acceptance Criteria

1. THE Frontend_Project SHALL reside in the `foremen-frontend/` directory at the workspace root as a sibling to `foremen-backend/`
2. THE Frontend_Project SHALL use Vite 6 as the build tool with the React plugin configured
3. THE Frontend_Project SHALL use React 19 and ReactDOM 19 as the UI framework
4. THE Frontend_Project SHALL use TypeScript 5 with strict mode enabled in `tsconfig.json`
5. THE Frontend_Project SHALL list all dependencies in `package.json` with pinned (exact) versions

### Requirement 2: Development Server Configuration

**User Story:** As a developer, I want the dev server configured with proxy and correct port so that I can develop against the backend without CORS issues.

#### Acceptance Criteria

1. THE Vite_Dev_Server SHALL listen on port 3000
2. THE Vite_Dev_Server SHALL proxy all requests matching `/api` to `http://localhost:8080`
3. WHEN the Vite_Dev_Server starts, THE Vite_Dev_Server SHALL enable hot module replacement for instant feedback during development

### Requirement 3: Design Token System

**User Story:** As a developer, I want design tokens defined as CSS custom properties so that the UI consistently matches the approved mockup.

#### Acceptance Criteria

1. THE Design_Token_System SHALL define `--background: #09090b` as the application background color
2. THE Design_Token_System SHALL define `--foreground: #fafafa` as the primary text color
3. THE Design_Token_System SHALL define `--card: #09090b` as the card surface color
4. THE Design_Token_System SHALL define `--card-foreground: #fafafa` as the card text color
5. THE Design_Token_System SHALL define `--border: #27272a` as the default border color
6. THE Design_Token_System SHALL define `--secondary: #27272a` as the secondary surface color
7. THE Design_Token_System SHALL define `--secondary-foreground: #fafafa` as the secondary text color
8. THE Design_Token_System SHALL define `--muted: #27272a` as the muted surface color
9. THE Design_Token_System SHALL define `--muted-foreground: #a1a1aa` as the muted text color
10. THE Design_Token_System SHALL define `--accent: #27272a` as the accent surface color
11. THE Design_Token_System SHALL define `--accent-foreground: #fafafa` as the accent text color
12. THE Design_Token_System SHALL define `--destructive: #7f1d1d` as the destructive action color
13. THE Design_Token_System SHALL define `--success: #22c55e` as the success state color
14. THE Design_Token_System SHALL define `--warning: #eab308` as the warning state color
15. THE Design_Token_System SHALL define `--info: #3b82f6` as the informational state color
16. THE Design_Token_System SHALL define `--primary: #fafafa` as the primary action color
17. THE Design_Token_System SHALL define `--primary-foreground: #18181b` as the primary action text color
18. THE Design_Token_System SHALL define `--input: #27272a` as the input border color
19. THE Design_Token_System SHALL define `--ring: #d4d4d8` as the focus ring color
20. THE Design_Token_System SHALL define `--radius: 8px` as the default border radius
21. THE Design_Token_System SHALL apply dark theme as the default appearance

### Requirement 4: Typography

**User Story:** As a developer, I want Inter font configured as the base typeface so that the UI matches the design mockup.

#### Acceptance Criteria

1. THE Frontend_Project SHALL use Inter as the primary font family with weights 300, 400, 500, 600, and 700
2. THE Frontend_Project SHALL set the base font size to 14px
3. THE Frontend_Project SHALL set the base line height to 1.5
4. THE Frontend_Project SHALL enable antialiased font rendering (`-webkit-font-smoothing: antialiased`)

### Requirement 5: Tailwind CSS Configuration

**User Story:** As a developer, I want Tailwind CSS 4 configured so that I can use utility-first styling throughout the application.

#### Acceptance Criteria

1. THE Frontend_Project SHALL use Tailwind CSS 4 as the utility-first CSS framework
2. THE Frontend_Project SHALL configure Tailwind to scan all source files in `src/` for class usage
3. THE Frontend_Project SHALL integrate design tokens with Tailwind theme configuration so that token values are accessible as Tailwind utilities

### Requirement 6: Component Library Setup

**User Story:** As a developer, I want shadcn/ui configured with New York style so that I have accessible, consistent UI primitives based on Radix.

#### Acceptance Criteria

1. THE Component_Library SHALL use shadcn/ui configured with the New York style variant
2. THE Component_Library SHALL install generated components into `src/components/ui/`
3. THE Component_Library SHALL use Radix primitives as the underlying accessibility layer
4. THE Component_Library SHALL integrate with the Design_Token_System for consistent theming

### Requirement 7: State Management

**User Story:** As a developer, I want TanStack Query for server state and Zustand for client state so that the app has predictable, separated state management patterns.

#### Acceptance Criteria

1. THE Frontend_Project SHALL use TanStack Query v5 for server state management (API data fetching, caching, synchronization)
2. THE Frontend_Project SHALL use Zustand for client-side state management (UI state, preferences)
3. THE Frontend_Project SHALL configure a TanStack Query client with sensible defaults (stale time, retry logic)

### Requirement 8: Routing

**User Story:** As a developer, I want React Router v7 set up so that the application supports client-side navigation.

#### Acceptance Criteria

1. THE Frontend_Project SHALL use React Router v7 as the client-side routing library
2. THE Frontend_Project SHALL configure a browser router at the application root

### Requirement 9: Forms and Validation

**User Story:** As a developer, I want React Hook Form and Zod configured so that form handling is performant and validation is type-safe.

#### Acceptance Criteria

1. THE Frontend_Project SHALL use React Hook Form for form state management
2. THE Frontend_Project SHALL use Zod for schema-based form validation
3. THE Frontend_Project SHALL integrate React Hook Form with Zod via the `@hookform/resolvers` package

### Requirement 10: Internationalization

**User Story:** As a developer, I want i18next configured with PL and RU translations so that the application supports both required languages from the start.

#### Acceptance Criteria

1. THE Frontend_Project SHALL use i18next with react-i18next for internationalization
2. THE Frontend_Project SHALL provide translation files for Polish (pl.json) and Russian (ru.json) located in `src/locales/`
3. THE Frontend_Project SHALL set Polish as the default language
4. THE Frontend_Project SHALL support language switching at runtime without page reload
5. WHEN a translation key is missing, THE Frontend_Project SHALL fall back to the key string

### Requirement 11: Icons

**User Story:** As a developer, I want Lucide React configured so that the application uses consistent, tree-shakeable icons.

#### Acceptance Criteria

1. THE Frontend_Project SHALL use Lucide React as the icon library
2. THE Frontend_Project SHALL support tree-shaking so that only used icons are included in the production bundle

### Requirement 12: Testing Infrastructure

**User Story:** As a developer, I want Vitest and Testing Library configured so that I can write unit and component tests from the start.

#### Acceptance Criteria

1. THE Test_Runner SHALL use Vitest as the test framework with jsdom environment
2. THE Test_Runner SHALL integrate Testing Library (`@testing-library/react`, `@testing-library/jest-dom`) for component testing
3. THE Test_Runner SHALL resolve the `@/` path alias in test files
4. THE Frontend_Project SHALL include a Playwright configuration file for future E2E test support

### Requirement 13: Project Structure

**User Story:** As a developer, I want a well-defined folder structure so that code is organized consistently across the team.

#### Acceptance Criteria

1. THE Frontend_Project SHALL organize source code under `src/` with the following subdirectories: `app/`, `components/`, `components/ui/`, `features/`, `hooks/`, `lib/`, `stores/`, `types/`, `locales/`
2. THE Frontend_Project SHALL place public static assets in a `public/` directory
3. THE Frontend_Project SHALL place Vitest test files in a `tests/` directory
4. THE Frontend_Project SHALL place Playwright E2E test files in an `e2e/` directory

### Requirement 14: Path Aliases

**User Story:** As a developer, I want `@/` path alias configured so that imports are short and refactor-friendly.

#### Acceptance Criteria

1. THE Frontend_Project SHALL configure TypeScript path alias `@/*` to resolve to `src/*`
2. THE Frontend_Project SHALL configure Vite to resolve the `@/` alias so that runtime and type-checking agree

### Requirement 15: Code Quality Tooling

**User Story:** As a developer, I want ESLint and Prettier configured so that code style is consistent and errors are caught early.

#### Acceptance Criteria

1. THE Frontend_Project SHALL configure ESLint with TypeScript and React rules
2. THE Frontend_Project SHALL configure Prettier for code formatting
3. THE Frontend_Project SHALL include npm scripts for linting (`lint`) and formatting (`format`)

### Requirement 16: Mobile-First Approach

**User Story:** As a developer, I want the project set up with a mobile-first mindset so that responsive design is the default.

#### Acceptance Criteria

1. THE Frontend_Project SHALL include a viewport meta tag configured for mobile devices (`width=device-width, initial-scale=1.0`)
2. THE Frontend_Project SHALL configure Tailwind breakpoints following the mobile-first convention (styles apply to smallest viewport first, `md:` and `lg:` for larger screens)

### Requirement 17: Full-Stack Docker Compose for Local Development

**User Story:** As a developer, I want a single `docker-compose.yml` at the project root that starts the entire stack (PostgreSQL, backend, frontend) so that I can run the full application locally with one command.

#### Acceptance Criteria

1. THE Frontend_Project workspace root SHALL contain a `docker-compose.yml` that defines services: `postgres`, `backend`, `frontend`
2. THE `postgres` service SHALL use the official PostgreSQL 16 image with healthcheck
3. THE `backend` service SHALL build from `foremen-backend/` (or use Gradle bootJar), depend on postgres, and expose port 8080
4. THE `frontend` service SHALL build from `foremen-frontend/` (Vite dev server or nginx for built assets), depend on backend, and expose port 3000
5. ALL sensitive parameters (database credentials, ports, secrets) SHALL be externalized into a `.env` file (not committed to git)
6. THE workspace root SHALL contain a `.env.example` file documenting all required environment variables with safe placeholder values
7. THE `.gitignore` SHALL exclude `.env` from version control
8. WHEN a developer runs `docker compose up`, THE full stack SHALL start and be accessible at `http://localhost:3000` (frontend) and `http://localhost:8080` (backend API)
