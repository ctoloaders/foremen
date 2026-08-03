# Requirements Document

## Introduction

Foremen — система управления комплексными ремонтами квартир. Веб-приложение, заменяющее текущий процесс ведения проектов в Excel. Система обеспечивает управление складом материалов, каталогом работ с расценками, расчёт смет на основании размеров помещений, планирование графиков работ и финансовый учёт. Архитектура: монолитный Java 25 / Spring Boot 6 бэкенд, React фронтенд, с перспективой мобильного приложения.

## Glossary

- **System**: Foremen — система управления ремонтами
- **Admin**: Представитель компании с полным доступом ко всем модулям системы
- **Worker**: Представитель компании с доступом к ограниченному набору модулей, назначенному Admin
- **Client**: Заказчик ремонта, имеющий доступ в режиме только чтения к оферте, смете, графику работ и финансовым документам
- **Project**: Объект ремонта (квартира), содержащий помещения, смету, материалы, график и финансы
- **Room**: Помещение объекта с размерами (площадь пола, площадь стен, обвод, высота, окна, двери)
- **Work_Catalog**: Справочник видов работ с единицами измерения и ценами по категориям
- **Work_Category**: Категория работ (Подготовительные работы, Конструкции и ГК, Сантехника черновая, Сантехника чистовая, Электрика черновая, Электрика чистовая, Плиточные работы, Шпаклёвка, Малярные работы, Полы, Столярка, Дополнительные, Прочие)
- **Estimate**: Смета — расчёт стоимости работ для Project на основании размеров Room и цен из Work_Catalog
- **Warehouse**: Склад строительных и отделочных материалов с учётом закупочных и продажных цен, НДС, финансовых документов
- **Construction_Materials**: Строительные материалы (клеи, грунтовки, профили, плиты ГК и т.д.)
- **Finishing_Materials**: Отделочные материалы (плитка, краска, обои, паркет и т.д.)
- **Invoice**: Фактура (входящая или исходящая) — финансовый документ
- **Settlement**: Расчёт финансов проекта — сумма по договору, изменения, стоимость материалов, оплаченное, остаток
- **Delivery_Schedule**: График доставок материалов и оборудования на объект с отслеживанием статуса
- **Work_Order**: Детальный наряд-заказ для бригады рабочих с перечнем работ, количеством и суммой
- **Worker_Price_List**: Прайс-лист для работников с базовой ценой, наценкой за оборудование и итоговой ставкой
- **Offer_Package**: Пакетное предложение (Budget / Norm / Lux) с предустановленными объёмами работ
- **Gantt_Chart**: Диаграмма-план выполнения работ по времени

## Requirements

### Requirement 1: Authentication and User Management

**User Story:** As an Admin, I want to manage user accounts and access, so that each participant has appropriate access to the system.

#### Acceptance Criteria

1. WHEN a new Client registration is initiated, THE System SHALL send a one-time password to the Client email address specified in the invitation, where the one-time password remains valid for 15 minutes from the time of issuance
2. WHEN a Client enters a valid one-time password within the validity period, THE System SHALL create a Client account and grant read-only access to the assigned Project
3. IF a Client enters an invalid or expired one-time password, THEN THE System SHALL reject the authentication attempt, display an error message indicating the password is invalid or expired, and allow the Client to request a new one-time password
4. THE System SHALL allow only Admin to create Worker accounts
5. WHEN Admin creates a Worker account, THE System SHALL allow Admin to assign module-level access permissions (Warehouse, Finances, Projects, Work_Catalog)
6. IF an authentication attempt fails three consecutive times, THEN THE System SHALL lock the account for 15 minutes
7. WHEN a User logs in successfully, THE System SHALL display the interface in the language selected in the User profile settings
8. IF no language preference is set in the User profile, THEN THE System SHALL default to English as the interface language
9. THE System SHALL support at minimum Polish, Russian, and English interface languages

### Requirement 2: Project Management

**User Story:** As an Admin, I want to create and manage renovation projects, so that all project data is organized and accessible in one place.

#### Acceptance Criteria

1. THE System SHALL allow Admin to create a new Project with required fields: name (max 200 characters), address (max 500 characters), total area (0.01 to 99999.99 m²), client assignment, manager assignment, contract start date, contract end date
2. WHEN a Project is created, THE System SHALL generate a summary view displaying one row per Work_Category with columns: net price, VAT 8% amount, and gross price, with all values initialized to 0.00 PLN until work items are added
3. THE System SHALL allow Admin to assign between 1 and 50 Workers and exactly one Client to a Project
4. WHEN Client accesses a Project, THE System SHALL display the Estimate summary, Gantt_Chart, financial settlement, and offer package in read-only mode without edit controls
5. WHEN a user views a Project, THE System SHALL display project metadata: manager full name, company name and address, document version (sequential integer starting at 1), and contract end date as validity date
6. IF Admin submits a Project creation form with any required field empty or contract end date earlier than contract start date, THEN THE System SHALL reject the submission and display a validation error message indicating the invalid fields

### Requirement 3: Room Management

**User Story:** As an Admin, I want to define rooms with dimensions for each project, so that work estimates can be calculated automatically.

#### Acceptance Criteria

1. THE System SHALL allow Admin to add rooms to a Project with a room type (przedpokój, hol, kuchnia, salon, biuro, master, pokój 1-N, łazienka 1-N) where N is between 1 and 20
2. WHEN a Room is added, THE System SHALL require the following dimension fields: floor area (m²), wall area (m²), perimeter (mb), door count (szt), door height (mb), door width (mb), door area (m²), missing wall (mb), unfinished area (mb), window height (mb), window width (mb), window area (m²), inner corners count (szt), ceiling height (mb), where all numeric values must be non-negative and have at most 2 decimal places
3. THE System SHALL auto-calculate derived values: door area as door height × door width × door count, window area as window height × window width, wall area as perimeter × ceiling height minus total door area minus window area
4. WHEN room dimensions are modified, THE System SHALL recalculate all dependent Estimate values within 2 seconds
5. THE System SHALL display a summary row per Work_Category across all rooms with total cost per room and per category
6. IF Admin enters a dimension value that is negative or non-numeric, THEN THE System SHALL reject the input and display a validation error indicating which field is invalid
7. THE System SHALL allow Admin to edit and delete existing rooms within a Project, and WHEN a room is deleted, THE System SHALL remove all associated Estimate values for that room
8. WHEN auto-calculation produces a wall area result that is zero or negative, THE System SHALL display a warning indicating that the entered dimensions may be inconsistent

### Requirement 4: Work Catalog Management

**User Story:** As an Admin, I want to maintain a catalog of renovation works with pricing, so that estimates are generated from standardized price data.

#### Acceptance Criteria

1. THE System SHALL organize works into 13 categories: Preliminary Works & Demolition, Constructions & Drywall, Plumbing Rough, Plumbing Finish, Electrical Rough, Electrical Finish, Tiling & Wall Coverings, Plastering & Surface Prep, Painting & Stucco & Decor, Floors & Baseboards, Carpentry (Doors), Extras, Other / Coordination / Non-standard
2. WHEN Admin adds a work item to Work_Catalog, THE System SHALL require: item number (LP, unique positive integer within its category), name in Polish (ZAKRES, maximum 200 characters), name in Russian (Zakres, maximum 200 characters), measurement unit (one of: m², mb, szt, kpl, godz), and unit price (net, PLN, value between 0.01 and 999999.99)
3. IF Admin submits a work item with a missing required field or a duplicate item number (LP) within the same category, THEN THE System SHALL reject the submission and display an error message indicating which field is invalid or duplicated
4. THE System SHALL allow Admin to define price tiers for each work item: base price, base + equipment (base price multiplied by 1.10), and contractor all-inclusive price (base price multiplied by 1.265)
5. WHEN Admin modifies or deletes a work item in Work_Catalog, THE System SHALL apply the change only to new and draft Estimates, and SHALL NOT alter prices in Estimates that have been confirmed by the client
6. THE System SHALL support bilingual work item names (Polish and Russian) for each catalog entry

### Requirement 5: Estimate Calculation

**User Story:** As an Admin, I want the system to calculate work estimates based on room dimensions and the work catalog, so that I can generate accurate quotes quickly.

#### Acceptance Criteria

1. WHEN Admin selects work items for a Room, THE System SHALL calculate the total cost for each item as unit_price × quantity, rounding the result to exactly 2 decimal places (zł)
2. WHEN Admin adds a work item to an Estimate, THE System SHALL allow Admin to either manually enter a quantity (between 0.01 and 999,999.99) or choose auto-calculate to derive the quantity from room dimensions based on the work item's unit type (m² from wall area or floor area, m from perimeter, m³ from volume)
3. IF Admin selects auto-calculate and the required room dimensions for the work item's unit type are not defined, THEN THE System SHALL prevent auto-calculation and display an error message indicating which dimensions are missing
4. THE System SHALL display the Estimate as a table with columns: work name, unit price, unit, quantity, total amount — grouped by Work_Category and sorted alphabetically by Work_Category name
5. THE System SHALL calculate subtotals per Work_Category and a grand total for the entire Project, each rounded to exactly 2 decimal places (zł)
6. WHEN an Estimate is confirmed, THE System SHALL lock all prices, quantities, and calculated totals, creating a versioned snapshot identified by an incrementing version number and confirmation timestamp
7. THE System SHALL support adding change orders to a confirmed Estimate, where each change order has its own itemized totals and the grand total is displayed as the confirmed estimate total plus the sum of all change order totals
8. THE System SHALL display cost per square meter (zł/m²) for the total project by dividing the grand total (including change orders) by the sum of all Room floor areas in the Project, rounded to 2 decimal places

### Requirement 6: Offer Packages

**User Story:** As an Admin, I want to generate package offers (Budget, Norm, Lux), so that Clients can choose a renovation tier with pre-configured work items and materials.

#### Acceptance Criteria

1. THE System SHALL support three offer packages: Budget, Norm, and Lux, where each package defines a distinct tier of materials quality and finish level for: walls finish type, flooring type, door type, bathroom configuration, lighting and electrical configuration, and included decorative elements
2. WHEN Admin selects a package tier and a project area (one or more rooms with measured dimensions), THE System SHALL generate a pre-filled Estimate with quantities calculated from the room dimensions for the selected package tier
3. IF Admin selects a package for a project area that has no room dimensions recorded, THEN THE System SHALL display an error message indicating that room measurements are required before package generation
4. WHEN Client views an offer package, THE System SHALL display: list of work items per room, materials included with unit quantities, warranty terms (36 months), and price breakdown showing labor cost, construction materials (with 20% markup), finishing materials, project fee, and grand total
5. THE System SHALL calculate separate totals per package: labor cost, construction materials cost (with 20% markup applied to base material cost), finishing materials cost, project fee (as a percentage of the subtotal), and grand total as the sum of all preceding cost components

### Requirement 7: Warehouse — Construction Materials

**User Story:** As a Worker (with warehouse access), I want to track construction material purchases, so that material costs are accurately recorded per project.

#### Acceptance Criteria

1. THE System SHALL allow authorized users to record construction material purchases with fields: date, store/warehouse (maximum 200 characters), description (maximum 500 characters), purchase gross amount (0.01 to 99,999,999.99), purchase VAT rate (default 23%), net amount (auto-calculated), selling VAT rate (default 8%), selling gross amount (auto-calculated), comment (maximum 1000 characters), and material category (Construction_Materials or Finishing_Materials)
2. WHEN a purchase is recorded, THE System SHALL auto-calculate the net amount as purchase gross amount divided by (1 + purchase VAT rate / 100), and the selling gross amount as net amount multiplied by (1 + selling VAT rate / 100), with results rounded to two decimal places
3. THE System SHALL display a running total of all purchases (gross, net, selling gross) for the current project at the top of the materials list, with separate totals shown for each material category
4. THE System SHALL separate materials into two categories: Construction_Materials and Finishing_Materials, each with separate tracking and totals
5. WHEN a material record is added, THE System SHALL link the record to the currently selected Project
6. IF a user submits a material purchase record with any required field (date, store/warehouse, description, purchase gross amount, material category) left empty or with a gross amount outside the allowed range, THEN THE System SHALL reject the submission and display an error message indicating which fields require correction
7. WHEN the user changes the purchase VAT rate or selling VAT rate on an existing material record, THE System SHALL recalculate the net amount and selling gross amount accordingly

### Requirement 8: Warehouse — Finishing Materials

**User Story:** As a Worker (with warehouse access), I want to track finishing material purchases separately, so that decorative and finishing costs are accounted for independently.

#### Acceptance Criteria

1. THE System SHALL allow authorized users to record finishing material purchases with fields: date, store/warehouse (max 200 characters), description (max 500 characters), purchase gross amount (0.01 to 999,999,999.99 PLN), purchase VAT rate (default 23%), net amount (auto-calculated), selling VAT rate (default 8%), selling gross amount (auto-calculated), comment (max 1000 characters)
2. WHEN a finishing material purchase is recorded, THE System SHALL auto-calculate net amount as purchase gross amount divided by (1 + purchase VAT rate / 100), and selling gross amount as net amount multiplied by (1 + selling VAT rate / 100)
3. THE System SHALL display finishing materials in a separate list from construction materials, showing columns: date, store/warehouse, description, purchase gross, VAT rate, net amount, selling VAT rate, selling gross, comment
4. THE System SHALL calculate and display totals at the top of the finishing materials list: total purchase gross, total net, total selling gross
5. WHEN a finishing material record is added, THE System SHALL link the record to the current Project
6. IF a user submits a finishing material record with any required field empty or with a purchase gross amount outside the range 0.01 to 999,999,999.99, THEN THE System SHALL reject the submission and display an error message indicating the invalid field

### Requirement 9: Financial Settlement

**User Story:** As an Admin, I want to track project financial settlement, so that I can monitor payments and outstanding amounts.

#### Acceptance Criteria

1. THE System SHALL display a financial summary with rows: Contract value (gross), Changes value, Construction materials value, Finishing materials value, and Total sum
2. THE System SHALL track three values for each financial category: total amount, settled amount, and remaining amount, each displayed as a numeric value with up to 2 decimal places
3. THE System SHALL allow Admin to create Invoice records with fields: invoice number (auto-incremented starting from 1), amount (numeric value between 0.01 and 999,999,999.99), paid status (boolean, default unpaid), issue date, and comment (free text, maximum 500 characters)
4. WHEN Admin creates an Invoice, THE System SHALL require the Admin to select one financial category (Contract value, Changes value, Construction materials value, or Finishing materials value) to associate the invoice with
5. WHEN an Invoice is marked as paid, THE System SHALL increase the settled amount in the associated financial category by the invoice amount
6. IF a paid Invoice is marked as unpaid, THEN THE System SHALL decrease the settled amount in the associated financial category by the invoice amount
7. THE System SHALL calculate remaining amount as total minus settled for each category
8. THE System SHALL support a default maximum of 9 invoices per financial category, with the ability for Admin to add additional invoice rows beyond 9 on demand

### Requirement 10: Delivery Schedule

**User Story:** As an Admin, I want to track material and equipment deliveries, so that construction teams know what is available and what is pending.

#### Acceptance Criteria

1. THE System SHALL allow Admin to create delivery schedule items with required fields: room location (max 100 characters), category (Bathroom Equipment, Bathroom Accessories, Decor, Tiles, Paints, Lighting, Flooring, Doors, Windows), product name (max 150 characters), delivery deadline (date), status; and optional fields: model/specification (max 200 characters), quantity (integer, 1 to 99999), link (valid URL, max 2000 characters), comment (max 500 characters)
2. THE System SHALL display delivery status labels in both Polish and Russian simultaneously for each status value
3. WHEN a delivery status changes, THE System SHALL record the timestamp of the change and retain previous status history
4. THE System SHALL group delivery items by room location and display them in a table format
5. THE System SHALL support delivery statuses: Nowe (New), Zamówione (Ordered), Dostarczone (Delivered), Anulowane (Cancelled), where transitions are allowed from any status to any other status
6. IF Admin submits a delivery schedule item with any required field empty or with field values exceeding the specified limits, THEN THE System SHALL reject the submission and display an error message indicating which fields are invalid

### Requirement 11: Work Order (Detailed Assignment)

**User Story:** As an Admin, I want to generate detailed work orders for worker crews, so that each worker knows their assigned scope and compensation.

#### Acceptance Criteria

1. THE System SHALL allow Admin to generate a Work_Order document containing: contractor company info (Foremen Sp. z o.o.), worker/subcontractor info (full name, contact phone number, tax identification number), investment/project name, work start date, work end date
2. THE System SHALL populate the Work_Order with selected work items: name, unit price (worker rate from Worker_Price_List), unit, quantity, and calculated total per item (unit_price × quantity)
3. THE System SHALL display work items grouped by Work_Category in the Work_Order
4. THE System SHALL support quantity breakdown by individual rooms (columns per room) within the Work_Order, where the sum of per-room quantities equals the total quantity for the work item
5. WHEN Admin confirms finalization of a Work_Order, THE System SHALL calculate the grand total (sum of all work item totals), lock the Work_Order against further edits, and record the finalization timestamp
6. IF Admin attempts to finalize a Work_Order that contains no work items, THEN THE System SHALL prevent finalization and display an error message indicating that at least one work item is required
7. THE System SHALL allow Admin to mark individual work items as completed per worker and generate settlement reports containing: worker name, Work_Category, work item name, unit price, completed quantity, and total per work item

### Requirement 12: Worker Price List

**User Story:** As an Admin, I want to maintain per-worker pricing, so that I can calculate labor costs at different rates for different worker types.

#### Acceptance Criteria

1. THE System SHALL maintain a Worker_Price_List with columns: item number, scope (Polish), scope (Russian), unit, base price after markup (40%), base price, margin percentage, base + equipment price (+10%), equipment margin percentage, contractor all-inclusive rate (+26.5%)
2. THE System SHALL allow Admin to define markup percentages per work category (range 0% to 200%) and override individual item prices (range 0.01 to 999,999.99 PLN)
3. WHEN Admin generates a Work_Order, THE System SHALL use the Worker_Price_List rate corresponding to the assigned worker type
4. IF Admin generates a Work_Order for a worker type that has no matching Worker_Price_List entry, THEN THE System SHALL display an error message indicating that a price list must be configured before the Work_Order can be generated
5. THE System SHALL display the calculated margin percentage between each pricing tier, rounded to 2 decimal places
6. THE System SHALL auto-calculate tier prices: base + equipment price as base price × 1.10, and contractor all-inclusive rate as base price × 1.265, each rounded to 2 decimal places

### Requirement 13: Gantt Chart / Work Schedule

**User Story:** As an Admin, I want to view and manage a timeline of renovation works, so that I can plan and monitor project progress.

#### Acceptance Criteria

1. THE System SHALL display a Gantt_Chart showing Work_Categories as rows and calendar weeks as columns
2. THE System SHALL allow Admin to set start and end dates for each Work_Category within a Project, where the start date is not earlier than the Project start date and the end date is not later than the Project end date
3. WHEN a work category timeline overlaps with another, THE System SHALL display both simultaneously as separate rows on the chart
4. WHEN Client accesses the Gantt_Chart, THE System SHALL display the schedule in read-only mode without controls for editing dates or statuses
5. THE System SHALL allow Admin to mark work phases as: Not Started, In Progress, Completed, or Delayed
6. IF Admin sets an end date earlier than the start date for a Work_Category, THEN THE System SHALL reject the entry and display an error message indicating that the end date must be equal to or later than the start date

### Requirement 14: Role-Based Access Control

**User Story:** As an Admin, I want the system to enforce role-based access, so that each user sees only the data appropriate for their role.

#### Acceptance Criteria

1. THE System SHALL enforce three access roles: Admin (full read-write access to all modules: Warehouse, Finances, Projects with dimensions, Work_Catalog, and user management), Worker (configurable read/write access to Admin-assigned modules), Client (read-only access to assigned Project's offer, estimate, schedule, and financial documents)
2. WHEN Worker accesses the system, THE System SHALL display only the modules assigned by Admin (any combination of: Warehouse, Finances, Projects with dimensions, Work_Catalog)
3. WHEN Client accesses the system, THE System SHALL display: Project offer/package, Estimate summary, Gantt_Chart, and financial settlement documents in read-only mode for only the Projects to which the Client has been assigned by Admin
4. IF a user attempts to access a restricted module, THEN THE System SHALL display an access denied message and log the attempt including user identifier, timestamp, and the resource that was requested
5. WHEN Admin modifies Worker module permissions, THE System SHALL apply the updated permissions to the affected Worker's subsequent requests within the same active session without requiring the Worker to re-authenticate
6. IF a Worker has no modules assigned, THEN THE System SHALL display an empty state indicating that no modules have been granted and prevent access to any module

### Requirement 15: Internationalization

**User Story:** As an Admin, I want the system to support multiple languages, so that workers and clients from different language backgrounds can use the system comfortably.

#### Acceptance Criteria

1. THE System SHALL support interface localization in Polish, Russian, and English, with all interface labels, navigation elements, and system messages available in each of the three supported languages
2. THE System SHALL store work catalog entries with names in Polish, Russian, and English
3. WHEN a User changes language preference, THE System SHALL display all interface labels, navigation, date/time formats, and system messages in the selected language without requiring the User to log out or reload the page
4. IF a work item name is not available in the User's preferred language, THEN THE System SHALL display the work item name in the default fallback language (Polish)
5. THE System SHALL maintain data content (project names, comments, descriptions) in the original entry language without translation
6. WHEN a new User account is created without an explicit language preference, THE System SHALL default the interface language to Polish
7. WHEN a User selects a language preference, THE System SHALL persist that preference and apply it on all subsequent sessions until the User changes it

### Requirement 16: Mockup Screens (HTML)

**User Story:** As a stakeholder, I want to see HTML mockup screens of the future application for each user role, so that I can validate the design before development.

#### Acceptance Criteria

1. THE System design SHALL include HTML mockups using the template-dark.html template for: Dashboard (Admin view), Project list, Room dimensions editor, Work catalog browser, Estimate calculator, Warehouse (construction materials), Warehouse (finishing materials), Financial settlement, Delivery schedule, Work order generator, Gantt chart, User management (Admin), Client portal (read-only view)
2. THE System mockups SHALL reflect the data structure and layout of the current Excel file to ease user transition
3. THE System mockups SHALL demonstrate navigation between modules via a sidebar or top navigation menu
4. THE System mockups SHALL include role-specific views: Admin sees all modules and edit controls, Worker sees assigned modules with edit controls, Client sees assigned project in read-only mode with no edit controls

### Requirement 17: Data References (Справочники)

**User Story:** As an Admin, I want the system to maintain reference data dictionaries, so that data entry is standardized and consistent.

#### Acceptance Criteria

1. THE System SHALL maintain the following reference dictionaries: Room Types (przedpokój, hol, kuchnia, salon, biuro, master, pokój, łazienka), Work Categories (13 categories), Measurement Units (m², mb, szt, kpl, godz, %), Delivery Statuses (Nowe, Zamówione, Dostarczone, Anulowane), Delivery Categories (Wyposażenie łazienki, Akcesorja łazienkowa, Dekor, Płytki, Farby, Oświetlenie, Podłoga, Drzwi, Okna), Material Categories (Construction, Finishing), Offer Packages (Budget, Norm, Lux), Worker Types (with rate tiers: minimum 1 and maximum 10 rate tiers per worker type), VAT Rates (23%, 8%, 0%)
2. THE System SHALL allow Admin to add, edit, and deactivate reference dictionary entries, where each entry contains at minimum a unique name (1 to 100 characters) and an active/inactive status
3. WHEN Admin edits the name of a reference dictionary entry that is already referenced in existing records, THE System SHALL update the display name in all existing records that reference that entry
4. WHEN a reference dictionary entry is deactivated, THE System SHALL retain the entry in all existing records (including in-progress and completed) but hide the entry from new selection lists
5. IF a user submits input that does not match an active entry in the applicable reference dictionary, THEN THE System SHALL reject the submission, indicate which field failed validation, and preserve all other entered data
6. THE System SHALL support a maximum of 500 entries per reference dictionary
