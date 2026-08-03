# Design Document: Foremen — Renovation Management System (HTML Mockups)

## Overview

This design specifies 13 HTML mockup screens for the Foremen renovation management system. Each mockup is a standalone `.html` file based on `template-dark.html` (dark theme, green accent `#00ff88`, Inter font, card-based layout). The mockups demonstrate the UI structure, navigation, data layout, and role-specific views for Admin, Worker, and Client roles.

The goal is to produce visual prototypes that mirror the Excel-based workflow currently used by Foremen Sp. z o.o., allowing stakeholders to validate screen design before any backend/frontend implementation begins.

**Deliverables:** 13 HTML files + 1 shared sidebar component (inline in each file).

## Architecture

### File Structure

```
/mockups/
├── 01-dashboard.html
├── 02-project-list.html
├── 03-room-dimensions.html
├── 04-work-catalog.html
├── 05-estimate-calculator.html
├── 06-warehouse-construction.html
├── 07-warehouse-finishing.html
├── 08-financial-settlement.html
├── 09-delivery-schedule.html
├── 10-work-order.html
├── 11-gantt-chart.html
├── 12-user-management.html
└── 13-client-portal.html
```

### Design Principles

1. **Template-based**: Every mockup inherits styles from `template-dark.html` (CSS variables, card layout, table styling, badges, highlights)
2. **Sidebar navigation**: Persistent left sidebar with module links, role-indicator badge, and project selector
3. **Data-driven**: Screen content mirrors actual Excel sheet structures (columns, groupings, totals)
4. **Role-aware**: Each screen shows/hides action buttons and edit controls based on the viewing role
5. **Polish-first UI**: All mockup interface labels, buttons, navigation, sample data, and column headers SHALL be in Polish. Russian equivalents are shown only in specific bilingual data fields (e.g., work item names in catalog) — not in the UI chrome itself.
6. **Responsive**: Layouts adapt to 768px+ (tablet/desktop) using the template's existing responsive breakpoints

### Navigation Structure

```
┌─────────────────────────────────────────────────────────────┐
│  HEADER: Logo (Foremen) | Project Selector | User Menu      │
├──────────┬──────────────────────────────────────────────────┤
│ SIDEBAR  │  MAIN CONTENT AREA                               │
│          │                                                   │
│ Dashboard│  [Cards / Tables / Forms depending on screen]     │
│ Projects │                                                   │
│ Rooms    │                                                   │
│ Catalog  │                                                   │
│ Estimate │                                                   │
│ ──────── │                                                   │
│ Warehouse│                                                   │
│  • Constr│                                                   │
│  • Finish│                                                   │
│ ──────── │                                                   │
│ Finances │                                                   │
│ Delivery │                                                   │
│ Orders   │                                                   │
│ Gantt    │                                                   │
│ ──────── │                                                   │
│ Users    │  (Admin only)                                     │
│          │                                                   │
└──────────┴──────────────────────────────────────────────────┘
```

**Sidebar visibility by role:**

| Module | Admin | Worker | Client |
|--------|-------|--------|--------|
| Dashboard | ✅ | ✅ | ❌ |
| Projects | ✅ | Per assignment | ❌ |
| Rooms | ✅ | Per assignment | ❌ |
| Work Catalog | ✅ | Per assignment | ❌ |
| Estimate | ✅ | Per assignment | ✅ (read-only) |
| Warehouse: Construction | ✅ | Per assignment | ❌ |
| Warehouse: Finishing | ✅ | Per assignment | ❌ |
| Finances | ✅ | Per assignment | ✅ (read-only) |
| Delivery Schedule | ✅ | Per assignment | ✅ (read-only) |
| Work Orders | ✅ | ❌ | ❌ |
| Gantt Chart | ✅ | ✅ | ✅ (read-only) |
| User Management | ✅ | ❌ | ❌ |
| Client Portal | ❌ | ❌ | ✅ |

## Components and Interfaces

### Shared UI Components (inline in each mockup)

1. **Sidebar Navigation** — vertical nav with icons, active state highlighting (accent border-left), section dividers, role badge at bottom
2. **Header Bar** — logo, project dropdown selector, language switcher (PL/RU/EN flags), user avatar + name + role badge, notification bell
3. **Data Table** — dark-themed table with sortable column headers (accent color), hover rows, sticky header, pagination footer
4. **Action Button Bar** — top-right button group: primary (green accent), secondary (outlined), destructive (red)
5. **Status Badge** — pill-shaped labels for statuses (green=active, orange=pending, red=overdue, gray=inactive)
6. **Summary Card** — metric display card with icon, label, value, and optional delta indicator
7. **Filter Bar** — horizontal row of dropdown filters + search input above data tables
8. **Modal Dialog** — overlay form for add/edit operations (dark card on backdrop)

### Color Coding for Status

| Status | Color | CSS Variable |
|--------|-------|--------------|
| Active / Delivered / Completed | `#00ff88` | `--accent` |
| In Progress / Ordered | `#3b82f6` | `--blue` |
| Pending / New | `#eab308` | `--yellow` |
| Overdue / Delayed | `#f97316` | `--orange` |
| Cancelled / Error | `#ef4444` | `--red` |

## Data Models

### Screen-by-Screen Specification

---

### Screen 1: Dashboard (Admin)

**File:** `01-dashboard.html`
**Maps to:** Overview / "Suma" sheet summary
**URL path:** `/dashboard`

**Layout:**
- Top row: 4 summary cards (Active Projects count, Total Estimate Value, Pending Deliveries, Unpaid Invoices)
- Middle row: Project progress bars (per-project completion %), recent activity feed
- Bottom row: Quick links to common actions

**Data displayed:**
| Element | Data Source |
|---------|------------|
| Active Projects | Count of projects with status "In Progress" |
| Total Estimate Value | Sum of all project grand totals (PLN) |
| Pending Deliveries | Count of delivery items with status "Zamówione" |
| Unpaid Invoices | Count of invoices with paid=false |
| Per-project progress | % of Work_Categories marked "Completed" |
| Recent Activity | Last 10 system events (created/edited/status changed) |

**Actions by role:**
| Action | Admin | Worker | Client |
|--------|-------|--------|--------|
| View all project stats | ✅ | ✅ (assigned only) | ❌ |
| Click to open project | ✅ | ✅ | ❌ |
| Create new project (button) | ✅ | ❌ | ❌ |

---

### Screen 2: Project List

**File:** `02-project-list.html`
**Maps to:** Project index
**URL path:** `/projects`

**Layout:**
- Filter bar: status filter, manager filter, date range
- Data table with project rows
- "+ New Project" button (Admin only)

**Table columns:**
| Column | Description |
|--------|-------------|
| # | Sequential number |
| Name | Project name (link to project detail) |
| Address | Project address |
| Client | Assigned client name |
| Manager | Assigned manager |
| Area (m²) | Total project area |
| Status | Badge: Draft / Active / Completed / Archived |
| Start Date | Contract start |
| End Date | Contract end |
| Estimate Total | Grand total (PLN) |

**Actions by role:**
| Action | Admin | Worker | Client |
|--------|-------|--------|--------|
| View project list | ✅ | ✅ (assigned) | ❌ |
| Create new project | ✅ | ❌ | ❌ |
| Edit project | ✅ | ❌ | ❌ |
| Delete/Archive project | ✅ | ❌ | ❌ |
| Open project detail | ✅ | ✅ | ❌ |

---

### Screen 3: Room Dimensions Editor

**File:** `03-room-dimensions.html`
**Maps to:** "Wymiary" sheet
**URL path:** `/projects/:id/rooms`

**Layout:**
- Project name header with breadcrumb
- Horizontal scrollable table: rooms as columns, dimension types as rows (mirrors Excel "Wymiary" exactly)
- Summary row at bottom per column (calculated totals)
- "+ Add Room" button in header

**Table structure (rows × columns):**
- **Rows (dimension types):** Powierzchnia podłogi (m²), Powierzchnia ścian (m²), Obwód (mb), Drzwi (szt), Wysokość drzwi (mb), Szerokość drzwi (mb), Pow. drzwi (m²) [auto], Brak ściany (mb), Nieobrób (mb), Wysokość okna (mb), Szerokość okna (mb), Pow. okna (m²) [auto], Narożniki wewn. (szt), Wysokość pomieszczenia (mb)
- **Columns:** One per room (Przedpokój, Hol, Kuchnia, Salon, Biuro, Master, Pokój 1..N, Łazienka 1..N)
- **Auto-calculated cells** highlighted with accent background

**Actions by role:**
| Action | Admin | Worker | Client |
|--------|-------|--------|--------|
| View dimensions table | ✅ | ✅ (if assigned) | ❌ |
| Add room (column) | ✅ | ❌ | ❌ |
| Edit dimension values | ✅ | ❌ | ❌ |
| Delete room | ✅ | ❌ | ❌ |
| See auto-calculated fields | ✅ | ✅ | ❌ |

---

### Screen 4: Work Catalog Browser

**File:** `04-work-catalog.html`
**Maps to:** Work catalog reference + "Pracownicy price" sheet
**URL path:** `/catalog`

**Layout:**
- Left: category filter (13 categories as collapsible accordion or vertical tabs)
- Right: scrollable table of work items for selected category
- Search bar at top
- "+ Add Work Item" button (Admin only)

**Table columns:**
| Column | Description |
|--------|-------------|
| LP | Item number within category |
| ZAKRES (PL) | Work name in Polish |
| Zakres (RU) | Work name in Russian |
| Unit | Measurement unit (m², mb, szt, kpl, godz) |
| Base Price (PLN) | Net unit price |
| +Equipment (+10%) | Auto-calculated |
| All-inclusive (+26.5%) | Auto-calculated |

**Categories (accordion/tabs):**
1. Prace przygotowawcze i wyburzenia
2. Konstrukcje i GK
3. Hydraulika (czarna)
4. Hydraulika (biała)
5. Elektryka (czarna)
6. Elektryka (biała)
7. Prace płytkarskie
8. Szpachle
9. Prace malarskie i dekoracyjne
10. Podłogi i listwy
11. Stolarka (drzwi)
12. Dodatkowe
13. Inne / Koordynacja / Niestandardowe

**Actions by role:**
| Action | Admin | Worker | Client |
|--------|-------|--------|--------|
| Browse/search catalog | ✅ | ✅ (if assigned) | ❌ |
| Add work item | ✅ | ❌ | ❌ |
| Edit work item | ✅ | ❌ | ❌ |
| Delete work item | ✅ | ❌ | ❌ |
| View price tiers | ✅ | ✅ | ❌ |

---

### Screen 5: Estimate Calculator

**File:** `05-estimate-calculator.html`
**Maps to:** "Suma" sheet + estimate generation
**URL path:** `/projects/:id/estimate`

**Layout:**
- Summary cards at top: Grand Total, Cost per m², Change Orders total
- Category summary table (like "Suma" sheet): one row per Work_Category with Net, VAT 8%, Gross
- Expandable rows: clicking a category shows individual work items with per-room quantities
- Package selector (Budget/Norm/Lux) for auto-fill
- "Confirm Estimate" / "Add Change Order" buttons

**Summary table (mirrors "Suma" sheet):**
| Column | Description |
|--------|-------------|
| Category | Work_Category name |
| Net (PLN) | Sum of all items in category |
| VAT 8% | Net × 0.08 |
| Gross (PLN) | Net + VAT |

**Expanded row (per work item):**
| Column | Description |
|--------|-------------|
| Work Name | From catalog |
| Unit Price | PLN |
| Unit | m², mb, szt, etc. |
| Qty (per room columns) | Quantity in each room |
| Total Qty | Sum across rooms |
| Total (PLN) | Unit price × Total Qty |

**Actions by role:**
| Action | Admin | Worker | Client |
|--------|-------|--------|--------|
| View estimate summary | ✅ | ✅ | ✅ (read-only) |
| Add work items to estimate | ✅ | ❌ | ❌ |
| Set quantities (manual/auto) | ✅ | ❌ | ❌ |
| Apply package (Budget/Norm/Lux) | ✅ | ❌ | ❌ |
| Confirm estimate (lock) | ✅ | ❌ | ❌ |
| Add change order | ✅ | ❌ | ❌ |

---

### Screen 6: Warehouse — Construction Materials

**File:** `06-warehouse-construction.html`
**Maps to:** "Mat. budowlane" sheet
**URL path:** `/projects/:id/warehouse/construction`

**Layout:**
- Summary cards: Total Purchase Gross, Total Net, Total Selling Gross
- Data table with material purchase records
- "+ Add Purchase" button
- Filter by date range, store

**Table columns (mirrors Excel):**
| Column | Description |
|--------|-------------|
| # | Row number |
| Data | Purchase date |
| Magazyn/Sklep | Store/warehouse name |
| Opis | Description of materials |
| Kwota brutto zakupu | Purchase gross amount (PLN) |
| VAT zakupu (%) | Purchase VAT rate (default 23%) |
| Kwota netto | Net amount (auto-calculated) |
| VAT sprzedaży (%) | Selling VAT rate (default 8%) |
| Kwota brutto sprzedaży | Selling gross (auto-calculated) |
| Komentarz | Free-text comment |

**Actions by role:**
| Action | Admin | Worker (warehouse) | Client |
|--------|-------|-------------------|--------|
| View materials list | ✅ | ✅ | ❌ |
| Add purchase record | ✅ | ✅ | ❌ |
| Edit purchase record | ✅ | ✅ | ❌ |
| Delete purchase record | ✅ | ❌ | ❌ |
| See totals | ✅ | ✅ | ❌ |

---

### Screen 7: Warehouse — Finishing Materials

**File:** `07-warehouse-finishing.html`
**Maps to:** "Mat. wykończeniowe" sheet
**URL path:** `/projects/:id/warehouse/finishing`

**Layout:** Identical structure to Screen 6, but for finishing materials category.

**Table columns:** Same as Screen 6.

**Actions by role:** Same as Screen 6.

**Difference:** Separate totals for finishing materials. Visual indicator (accent badge "Materiały wykończeniowe") to distinguish from construction materials tab.

---

### Screen 8: Financial Settlement

**File:** `08-financial-settlement.html`
**Maps to:** "Rozliczenie" sheet
**URL path:** `/projects/:id/finances`

**Layout:**
- Top: Financial summary table (4 rows × 3 value columns)
- Bottom: Invoice list per category (expandable sections)
- "+ Add Invoice" button

**Summary table structure (mirrors "Rozliczenie"):**
| Row | Total | Settled | Remaining |
|-----|-------|---------|-----------|
| Contract Value (gross) | PLN | PLN | PLN |
| Changes Value | PLN | PLN | PLN |
| Construction Materials | PLN | PLN | PLN |
| Finishing Materials | PLN | PLN | PLN |
| **TOTAL** | **PLN** | **PLN** | **PLN** |

**Invoice list columns:**
| Column | Description |
|--------|-------------|
| Invoice # | Auto-incremented |
| Amount (PLN) | Invoice value |
| Status | Paid ✅ / Unpaid ⏳ |
| Issue Date | Date |
| Category | Which financial row |
| Comment | Free text |
| Action | Mark Paid / Edit / Delete |

**Actions by role:**
| Action | Admin | Worker (finances) | Client |
|--------|-------|-------------------|--------|
| View financial summary | ✅ | ✅ | ✅ (read-only) |
| Add invoice | ✅ | ❌ | ❌ |
| Edit invoice | ✅ | ❌ | ❌ |
| Mark paid/unpaid | ✅ | ❌ | ❌ |
| Delete invoice | ✅ | ❌ | ❌ |

---

### Screen 9: Delivery Schedule

**File:** `09-delivery-schedule.html`
**Maps to:** "Harmonogram dostaw" sheet
**URL path:** `/projects/:id/deliveries`

**Layout:**
- Filter bar: room filter, category filter, status filter
- Data table grouped by room location
- Status badges with PL/RU labels
- "+ Add Delivery Item" button

**Table columns (mirrors Excel):**
| Column | Description |
|--------|-------------|
| Pomieszczenie | Room location |
| Kategoria | Delivery category (Bathroom Equipment, Tiles, etc.) |
| Nazwa produktu | Product name |
| Model/Spec | Model or specification |
| Ilość | Quantity |
| Termin dostawy | Delivery deadline |
| Status (PL/RU) | Nowe/Новое, Zamówione/Заказано, Dostarczone/Доставлено, Anulowane/Отменено |
| Link | URL to product |
| Komentarz | Comment |

**Actions by role:**
| Action | Admin | Worker | Client |
|--------|-------|--------|--------|
| View delivery schedule | ✅ | ✅ | ✅ (read-only) |
| Add delivery item | ✅ | ❌ | ❌ |
| Edit delivery item | ✅ | ❌ | ❌ |
| Change status | ✅ | ❌ | ❌ |
| Delete delivery item | ✅ | ❌ | ❌ |

---

### Screen 10: Work Order Generator

**File:** `10-work-order.html`
**Maps to:** "ZLECENIE SZCZEGÓŁOWE" sheet
**URL path:** `/projects/:id/orders`

**Layout:**
- Header: Contractor info (Foremen Sp. z o.o.), Worker/subcontractor info fields
- Work items table grouped by Work_Category
- Per-room quantity columns (like Excel: rooms as sub-columns)
- Grand total at bottom
- "Finalize Order" button, "Print/Export" button

**Header fields:**
- Contractor: Foremen Sp. z o.o. (pre-filled)
- Worker: Name, Phone, NIP (tax ID)
- Project: Name (auto-filled from context)
- Dates: Start date, End date

**Table columns (mirrors "ZLECENIE SZCZEGÓŁOWE"):**
| Column | Description |
|--------|-------------|
| LP | Item number |
| Zakres prac | Work name |
| Cena jedn. | Unit price (from Worker_Price_List) |
| Jednostka | Unit |
| Room 1 qty | Quantity for room 1 |
| Room 2 qty | Quantity for room 2 |
| ... | (column per room) |
| Razem ilość | Total quantity |
| Suma (PLN) | Total = unit price × total qty |

**Category subtotal rows** between groups.
**Grand total row** at bottom.

**Actions by role:**
| Action | Admin | Worker | Client |
|--------|-------|--------|--------|
| View work orders | ✅ | ❌ | ❌ |
| Create work order | ✅ | ❌ | ❌ |
| Select work items | ✅ | ❌ | ❌ |
| Set per-room quantities | ✅ | ❌ | ❌ |
| Finalize (lock) order | ✅ | ❌ | ❌ |
| Mark items completed | ✅ | ❌ | ❌ |
| Print/Export PDF | ✅ | ❌ | ❌ |

---

### Screen 11: Gantt Chart

**File:** `11-gantt-chart.html`
**Maps to:** Timeline/schedule view
**URL path:** `/projects/:id/gantt`

**Layout:**
- Left column: Work_Category names (13 rows)
- Right area: Horizontal timeline (weeks as columns, scrollable)
- Colored bars indicating duration per category
- Status indicators on bars (Not Started, In Progress, Completed, Delayed)
- Date range header showing calendar weeks

**Gantt row data:**
| Row (Category) | Start Date | End Date | Status | Bar Color |
|----------------|-----------|----------|--------|-----------|
| Prace przygotowawcze | date | date | badge | accent/blue/orange/red |
| Konstrukcje i GK | date | date | badge | ... |
| ... (13 categories) | ... | ... | ... | ... |

**Status colors on bars:**
- Not Started: `--text-muted` (gray)
- In Progress: `--blue`
- Completed: `--accent` (green)
- Delayed: `--orange`

**Actions by role:**
| Action | Admin | Worker | Client |
|--------|-------|--------|--------|
| View Gantt chart | ✅ | ✅ | ✅ (read-only) |
| Set start/end dates | ✅ | ❌ | ❌ |
| Change phase status | ✅ | ❌ | ❌ |
| Scroll/zoom timeline | ✅ | ✅ | ✅ |

---

### Screen 12: User Management (Admin Only)

**File:** `12-user-management.html`
**Maps to:** Admin panel
**URL path:** `/admin/users`

**Layout:**
- Tab bar: All Users | Workers | Clients
- Data table with user list
- "+ Invite User" button
- Permission editor (modal or side panel)

**Table columns:**
| Column | Description |
|--------|-------------|
| # | Row number |
| Name | Full name |
| Email | Email address |
| Role | Admin / Worker / Client (badge) |
| Language | PL / RU / EN |
| Status | Active / Locked / Pending |
| Assigned Projects | Project names (comma-separated) |
| Modules | Worker module permissions (badges) |
| Last Login | Timestamp |
| Actions | Edit / Deactivate / Reset Password |

**Actions by role:**
| Action | Admin | Worker | Client |
|--------|-------|--------|--------|
| View user list | ✅ | ❌ | ❌ |
| Create Worker account | ✅ | ❌ | ❌ |
| Invite Client (send OTP) | ✅ | ❌ | ❌ |
| Assign modules to Worker | ✅ | ❌ | ❌ |
| Assign projects to users | ✅ | ❌ | ❌ |
| Deactivate user | ✅ | ❌ | ❌ |
| Reset password | ✅ | ❌ | ❌ |

---

### Screen 13: Client Portal

**File:** `13-client-portal.html`
**Maps to:** Client-facing read-only view
**URL path:** `/portal`

**Layout:**
- Simplified sidebar with only: Offer, Estimate, Schedule, Finances
- No edit buttons, no action buttons
- Data presented in clean read-only cards and tables
- Project summary header with client name, project name, date

**Sections visible to Client:**

1. **Offer/Package** — Selected package (Budget/Norm/Lux) with itemized breakdown: labor, construction materials (with markup), finishing materials, project fee, grand total, warranty terms (36 months)
2. **Estimate Summary** — Category totals table (same as Screen 5 summary, no expanded editing)
3. **Gantt Schedule** — Read-only timeline view (same as Screen 11, no controls)
4. **Financial Documents** — Settlement summary table + invoice list (same as Screen 8, no action buttons)

**Actions by role:**
| Action | Admin | Worker | Client |
|--------|-------|--------|--------|
| View portal | ❌ | ❌ | ✅ |
| Download/Print documents | ❌ | ❌ | ✅ |
| Edit anything | ❌ | ❌ | ❌ |

---

## Error Handling

Since this phase covers HTML mockups only (no backend logic), error handling is demonstrated visually:

1. **Validation errors** — shown as red-bordered input fields with error text below (using `--red` color)
2. **Empty states** — illustrated with centered icon + "No data" message + action button
3. **Access denied** — shown as a card with lock icon and "Brak dostępu / Нет доступа / Access Denied" message
4. **Loading states** — skeleton placeholder cards with pulsing animation
5. **Confirmation dialogs** — modal overlay for destructive actions (delete, finalize)

Each mockup will include at least one error/empty state example where applicable.

## Testing Strategy

### PBT Assessment

Property-based testing is **NOT applicable** for this feature. The deliverables are static HTML mockup files — they represent UI rendering and layout, not algorithmic logic with inputs/outputs. There are no pure functions, data transformations, or universal properties to test across generated inputs.

### Applicable Testing Approach

1. **Visual Review** — stakeholder sign-off on each mockup (manual)
2. **HTML Validation** — W3C validator check for each file (automated, single pass)
3. **Accessibility Check** — WAVE or axe-core scan for contrast, aria labels, semantic HTML (automated, single pass)
4. **Responsive Check** — manual verification at 768px and 1200px breakpoints
5. **Cross-browser Check** — visual verification in Chrome, Firefox, Safari
6. **Checklist Verification** — each mockup must include:
   - Correct sidebar navigation for the demonstrated role
   - All data columns from the corresponding Excel sheet
   - Appropriate action buttons (shown/hidden per role)
   - All UI labels, buttons, navigation, headers, and sample data in Polish
   - Consistent use of template-dark.html styling (no custom CSS overrides of theme variables)

### Acceptance Criteria for Mockups

Each HTML file passes when:
- It renders correctly in a browser with all template styles applied
- The data structure matches the corresponding Excel sheet layout
- Role-specific controls are correctly shown/hidden
- Navigation sidebar reflects the correct module visibility for the demonstrated role
- All UI text is in Polish; bilingual data labels (PL/RU) are present only in catalog work item names
