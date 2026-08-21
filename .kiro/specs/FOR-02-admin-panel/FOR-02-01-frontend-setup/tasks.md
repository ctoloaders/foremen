# Implementation Plan: Frontend Project Setup (FOR-02-01-frontend-setup)

## Overview

Initialize the `foremen-frontend/` project with Vite 6, React 19, TypeScript 5, Tailwind CSS 4, shadcn/ui, state management, routing, i18n, testing infrastructure, and full-stack Docker Compose. Each task builds incrementally, ending with a fully wired and verified frontend scaffold.

## Tasks

- [x] 1. Initialize project and build tooling
  - [x] 1.1 Scaffold Vite 6 + React 19 + TypeScript 5 project
    - Create `foremen-frontend/` directory at workspace root
    - Initialize `package.json` with all dependencies pinned to exact versions (see design Package Dependencies Map)
    - Create `index.html` with viewport meta tag (`width=device-width, initial-scale=1.0`), `lang="pl"`, and module script pointing to `src/main.tsx`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 16.1_

  - [x] 1.2 Configure TypeScript with strict mode and path aliases
    - Create `tsconfig.json` as project references root (pointing to `tsconfig.app.json` and `tsconfig.node.json`)
    - Create `tsconfig.app.json` with `strict: true`, `noUncheckedIndexedAccess: true`, `noImplicitOverride: true`, path alias `@/*` → `src/*`, JSX support
    - Create `tsconfig.node.json` for Node-side files (vite.config.ts, playwright.config.ts)
    - _Requirements: 1.4, 14.1_

  - [x] 1.3 Create Vite configuration with proxy and path alias
    - Create `vite.config.ts` with `@vitejs/plugin-react`, `@tailwindcss/vite`, `@/` alias, port 3000, and `/api` proxy to `http://localhost:8080`
    - _Requirements: 2.1, 2.2, 2.3, 14.2_

- [x] 2. Set up styling, design tokens, and component library
  - [x] 2.1 Create Tailwind CSS 4 + design tokens stylesheet
    - Create `src/index.css` with `@import "tailwindcss"`, `@theme` block containing all color tokens, `:root` CSS custom properties for shadcn/ui compatibility, Inter font import, and body styles (font-size 14px, line-height 1.5, antialiased rendering, dark background/foreground)
    - _Requirements: 3.1–3.21, 4.1–4.4, 5.1–5.3_

  - [x] 2.2 Configure shadcn/ui component library
    - Create `components.json` with New York style, correct aliases (`@/components`, `@/lib/utils`, `@/components/ui`, `@/hooks`, `@/lib`), and CSS path
    - Create `src/lib/utils.ts` with the `cn()` utility function (clsx + tailwind-merge)
    - Create `src/components/ui/` directory (empty, ready for generated components)
    - _Requirements: 6.1–6.4_

- [x] 3. Set up state management, routing, and forms
  - [x] 3.1 Configure TanStack Query client
    - Create `src/lib/query-client.ts` with `QueryClient` configured: staleTime 5 minutes, retry 1, refetchOnWindowFocus false
    - _Requirements: 7.1, 7.3_

  - [x] 3.2 Create Zustand store scaffold
    - Create `src/stores/` directory with `ui-store.ts` implementing the `UIState` interface (sidebarOpen, locale, toggleSidebar, setLocale)
    - _Requirements: 7.2_

  - [x] 3.3 Configure React Router v7
    - Create `src/app/router.tsx` with `createBrowserRouter` and a root route placeholder
    - _Requirements: 8.1, 8.2_

  - [x] 3.4 Install forms and validation dependencies
    - Ensure `react-hook-form`, `zod`, and `@hookform/resolvers` are in `package.json` (no global config needed — used per-feature)
    - _Requirements: 9.1–9.3_

- [x] 4. Set up internationalization, icons, and application entry
  - [x] 4.1 Configure i18next with PL and RU translations
    - Create `src/lib/i18n.ts` with i18next + react-i18next initialization, PL as default language, `parseMissingKeyHandler` returning the key
    - Create `src/locales/pl.json` with common and nav translation keys
    - Create `src/locales/ru.json` with equivalent Russian translations
    - _Requirements: 10.1–10.5_

  - [x] 4.2 Create application entry point
    - Create `src/main.tsx` that imports i18n, wraps the app with `QueryClientProvider` and `RouterProvider`, renders into `#root` inside `React.StrictMode`
    - _Requirements: 1.2, 1.3, 7.1, 8.2_

  - [x] 4.3 Create project folder structure
    - Create all required directories: `src/app/`, `src/components/`, `src/components/ui/`, `src/features/`, `src/hooks/`, `src/lib/`, `src/stores/`, `src/types/`, `src/locales/`, `public/`, `tests/`, `e2e/`
    - Add `.gitkeep` files in empty directories to ensure they are tracked
    - _Requirements: 13.1–13.4_

- [x] 5. Checkpoint - Verify core setup
  - Ensure `npm install` succeeds without errors, `tsc --noEmit` passes, and all source files compile. Ask the user if questions arise.

- [x] 6. Set up testing and code quality
  - [x] 6.1 Configure Vitest with Testing Library
    - Create `vitest.config.ts` with jsdom environment, `@/` alias, globals, and setup file path
    - Create `tests/setup.ts` importing `@testing-library/jest-dom`
    - _Requirements: 12.1–12.3_

  - [x] 6.2 Create Playwright configuration
    - Create `playwright.config.ts` with testDir `./e2e`, webServer pointing to `npm run dev` on port 3000, baseURL `http://localhost:3000`
    - _Requirements: 12.4_

  - [x] 6.3 Configure ESLint and Prettier
    - Create `eslint.config.js` with flat config: `@eslint/js` recommended, `typescript-eslint`, `eslint-plugin-react-hooks`, `eslint-plugin-react-refresh`
    - Create `.prettierrc` with semi: false, singleQuote: true, trailingComma: all, printWidth: 100, tabWidth: 2
    - Add npm scripts: `lint` (`eslint .`), `format` (`prettier --write .`), `test` (`vitest --run`), `test:watch` (`vitest`), `dev`, `build`, `preview`
    - _Requirements: 15.1–15.3_

  - [x] 6.4 Write smoke test to verify infrastructure
    - Create `tests/app.test.tsx` placeholder test that asserts Vitest, path aliases, and test setup are correctly wired
    - Run `npm run test` to verify it passes
    - _Requirements: 12.1–12.3_

- [x] 7. Docker Compose and environment configuration
  - [x] 7.1 Create full-stack Docker Compose at workspace root
    - Create `docker-compose.yml` with `postgres` (PostgreSQL 16, healthcheck), `backend` (builds from foremen-backend, depends on postgres, port 8080), `frontend` (builds from foremen-frontend, depends on backend, port 3000), and `pgdata` volume
    - _Requirements: 17.1–17.4, 17.8_

  - [x] 7.2 Create environment configuration
    - Create `.env.example` at workspace root with `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT` placeholder values
    - Ensure `.gitignore` at workspace root excludes `.env`
    - _Requirements: 17.5–17.7_

  - [x] 7.3 Create frontend Dockerfile
    - Create `foremen-frontend/Dockerfile` for building and serving the frontend (multi-stage: build with Node, serve with nginx or Vite preview)
    - _Requirements: 17.4_

- [x] 8. Final checkpoint - Full verification
  - Ensure `npm install` succeeds, `npm run build` produces `dist/`, `npm run lint` passes, `npm run test` passes. Ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- The design explicitly states property-based testing does NOT apply to this project scaffolding feature
- Lucide React (Requirement 11) is satisfied by including it in `package.json` dependencies during task 1.1; tree-shaking is provided by Vite's default ES module bundling
- Icons (Requirement 11) and Tailwind breakpoints (Requirement 16.2) are built-in capabilities of the installed tools — no additional configuration files needed

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "1.3"] },
    { "id": 2, "tasks": ["2.1", "2.2", "4.3"] },
    { "id": 3, "tasks": ["3.1", "3.2", "3.3", "3.4", "4.1"] },
    { "id": 4, "tasks": ["4.2"] },
    { "id": 5, "tasks": ["6.1", "6.2", "6.3", "7.1", "7.2", "7.3"] },
    { "id": 6, "tasks": ["6.4"] }
  ]
}
```
