# FOR-02: Админ-панель (веб-приложение)

## Цель

Создать веб-приложение (SPA), которое станет основой всей платформы Foremen. На этом этапе реализуются:
- Фронтенд-проект (React + TypeScript + Vite + Tailwind CSS)
- Бекенд-сущности ABAC-модели: Role, Resource, Operation, User
- Управление ролями из UI (CRUD ролей, матрица доступов)
- Seed базовых ролей через Liquibase
- Shell приложения: sidebar, routing, layout (mobile-first)

**Без:** логина, аутентификации, разделения по ролям в runtime — это FOR-03.

## Дизайн-принципы

1. **Mobile-first** — каждая UI-спека сначала мобильная версия, затем десктопная
2. **Dark theme** — основная тема (как в mockups: bg #09090b, green accent)
3. **Компонентная архитектура** — переиспользуемые UI-компоненты с самого начала
4. **ABAC foundation** — модель доступа закладывается на уровне данных, enforcement в FOR-03

## Тех стек фронтенда (определяется в FOR-02-01)

| Категория | Технология |
|-----------|------------|
| Framework | React 19 |
| Язык | TypeScript 5 |
| Сборка | Vite 6 |
| Стили | Tailwind CSS 4 |
| Компоненты | shadcn/ui (Radix primitives) |
| State (серверный) | TanStack Query v5 |
| State (клиентский) | Zustand |
| Формы | React Hook Form + Zod |
| Роутинг | React Router v7 |
| i18n | i18next + react-i18next |
| Иконки | Lucide React |
| Тестирование | Vitest + Testing Library + Playwright |

## Сущности ABAC-модели

```
┌──────────┐      ┌──────────────┐      ┌───────────┐
│   Role   │──M:N─│ RoleResource │──M:1─│ Resource  │
│          │      │  (операции)  │      │           │
└──────────┘      └──────────────┘      └───────────┘
                         │ M:N
                         ▼
                  ┌───────────┐
                  │ Operation │
                  └───────────┘

┌──────────────────────────────────────┐
│   User                               │
│   - name, email, phone, active       │
│   - role_id (FK → Role)              │
│   - locale (VARCHAR: 'ru','pl','en') │
│   - display_preferences (JSONB)      │
│     { colorScheme, fontSize }        │
└──────────────────────────────────────┘
```

**Resource** — модуль/сущность системы (projects, rooms, estimate, warehouse...)
**Operation** — действие над ресурсом (create, read, update, delete)
**Role** — именованный набор разрешений (Admin, Manager, Foreman, Worker, Financier + custom)
**User** — пользователь с привязкой к роли

## Базовые роли (seed через Liquibase)

| Роль | Описание |
|------|----------|
| ADMIN | Полный доступ ко всем ресурсам и операциям, без ограничений |
| MANAGER | Менеджер проектов — доступ по матрице |
| FOREMAN | Прораб — доступ по матрице |
| WORKER | Рабочий — доступ по матрице |
| FINANCIER | Финансист — доступ по матрице |

## Стадии работы (дочерние спеки)

| # | Спека | Описание | Зависит от |
|---|-------|----------|------------|
| 01 | FOR-02-01-frontend-setup | Инициализация foremen-frontend: Vite + React + TS + Tailwind + shadcn/ui + структура проекта + дизайн-токены + base layout | — |
| 02 | FOR-02-02-app-shell | Shell приложения: sidebar navigation, top bar, responsive layout (mobile: bottom nav / drawer), routing structure, placeholder pages со skeleton-загрузкой (Suspense + shimmer pre-render списков/карточек) | FOR-02-01 |
| 03 | FOR-02-03-abac-entities | Бекенд: JPA-сущности Role, Resource, Operation, RoleResource + DAO/Service/Controller (на базе FOR-01 CRUD-фреймворка) | FOR-01 |
| 04 | FOR-02-04-abac-seed | Liquibase: создание таблиц + seed базовых ролей (ADMIN, MANAGER, FOREMAN, WORKER, FINANCIER) + ресурсов + операций + начальная матрица доступов | FOR-02-03 |
| 05 | FOR-02-05-user-entity | Бекенд: JPA-сущность User (name, email, phone, role_id, active, locale, display_preferences JSONB) + DAO/Service/Controller. locale — язык для уведомлений (отдельная колонка), display_preferences — тема/шрифт (JSONB) | FOR-02-03 |
| 06 | FOR-02-06-roles-ui | Фронтенд: страница управления ролями — список ролей, создание/редактирование роли, матрица доступов (resource × operation checkboxes) | FOR-02-02, FOR-02-04 |
| 07 | FOR-02-07-users-ui | Фронтенд: страница управления пользователями — список, создание/редактирование (имя, email, телефон, роль), деактивация | FOR-02-05, FOR-02-06 |
| 08 | FOR-02-08-theme-settings | Фронтенд + бекенд: страница «Настройки оформления» — **переключалка темы (dark/light/system)** с запоминанием в преференсах пользователя, выбор цветовой схемы (preset палитры как в shadcn/ui/create), размер шрифта (sm/default/lg/xl), live preview, сохранение per-user на бекенде (display_preferences JSONB), до логина — localStorage fallback, загрузка при старте, применение через CSS-переменные/Tailwind dark mode | FOR-02-05, FOR-02-02 |

### Граф зависимостей

```
FOR-02-01 → FOR-02-02 → FOR-02-06 → FOR-02-07
                  │           ↑            ↑
                  │  FOR-02-03 → FOR-02-04─┘
                  │       │                │
                  │       └→ FOR-02-05 ────┘
                  │              │
                  └──────────────┼──→ FOR-02-08
                                └────────↑
```

## Адаптация UI под mobile-first

- **Mobile (< 768px):** bottom navigation bar (5 icons), hamburger → slide-out drawer для полного меню, одноколоночный layout
- **Tablet (768–1024px):** collapsed sidebar (иконки), расширяется при наведении
- **Desktop (> 1024px):** фиксированный sidebar 240px (как в mockup)

Каждая UI-спека (06, 07 и далее) реализует:
1. Mobile layout + interactions
2. Desktop layout + interactions
3. Responsive transitions

---

*Создано: август 2026*
