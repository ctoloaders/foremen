// --- Project domain types (FOR-04-13) ---
//
// Authored across tasks 11.1 (DTOs, requests, address/status), 11.2 (member
// summary consumed by cells), and 11.4 (address-selection + client-block UI
// types). Kept as one module so the feature has a single type surface.

/** Re-export PaginatedResponse / FetchParams from data-table for feature-local imports. */
export type { PaginatedResponse, FetchParams } from '@/components/data-table'

/**
 * The five project lifecycle states (mirrors the backend `ProjectStatus` enum,
 * declaration order: DRAFT, ACTIVE, ON_HOLD, COMPLETED, CANCELLED).
 */
export type ProjectStatus =
  | 'DRAFT'
  | 'ACTIVE'
  | 'ON_HOLD'
  | 'COMPLETED'
  | 'CANCELLED'

/**
 * All defined {@link ProjectStatus} values in declaration order (single source for badges/selects
 * and the zod `status` enum). Mirrors the backend `ProjectStatus` enum order.
 */
export const PROJECT_STATUSES: readonly ProjectStatus[] = [
  'DRAFT',
  'ACTIVE',
  'ON_HOLD',
  'COMPLETED',
  'CANCELLED',
] as const

// --- Backend-mirrored Google Places DTOs (`/api/addresses/*` proxy shapes) ---

/**
 * A Google Places autocomplete prediction as returned by `GET /api/addresses/autocomplete`.
 * Mirrors the backend `PlacePredictionDto` (Requirement 5.1). The server-side API key is never
 * part of this shape.
 */
export interface PlacePredictionDto {
  description: string
  placeId: string
}

/** A single normalized Google Places address component (mirrors backend `PlaceComponentDto`). */
export interface PlaceComponentDto {
  longName: string
  shortName: string
  types: string[]
}

/**
 * Normalized Google Places details for a resolved `placeId`, returned by
 * `GET /api/addresses/details`. Mirrors the backend `PlaceDetailsDto` (Requirement 5.2).
 */
export interface PlaceDetailsDto {
  formattedAddress: string
  latitude: number | null
  longitude: number | null
  components: PlaceComponentDto[]
}

/**
 * Convenience alias for {@link PlacePredictionDto} used by the address autocomplete UI (task 11.4).
 */
export type AddressPrediction = PlacePredictionDto

/**
 * Convenience alias for {@link PlaceDetailsDto} used by the address autocomplete UI (task 11.4).
 */
export type AddressDetails = PlaceDetailsDto

/**
 * The subset of project-form state written by {@link GoogleAddressAutocomplete} once a prediction
 * is selected and resolved. The form owns the full state; the autocomplete only produces these
 * four fields (Requirement 8.4).
 */
export interface AddressSelection {
  googlePlaceId: string
  formattedAddress: string
  latitude: number | null
  longitude: number | null
}

// --- Project team member summary + list/read DTOs ---

/**
 * A single project-team member as exposed by the list/read DTOs. Mirrors the backend
 * `ProjectMemberSummaryDto` (Requirement 3.4). Materialized from `project_members` at projection
 * time; `userId` may be null for a freshly created (not-yet-persisted) invited client.
 */
export interface ProjectMemberSummaryDto {
  userId: number | null
  userName: string
  roleCode: string
  roleName: string
}

/**
 * Returned by `GET /api/projects` (paginated list view). Mirrors the backend `ProjectListDto`
 * (Requirement 3.4, design Change 2/3): base fields plus the `members` collection and a derived
 * `client` (the single member whose `roleCode === 'CLIENT'`, else `null`).
 */
export interface ProjectListDto {
  id: number
  name: string
  address: string | null
  googlePlaceId: string | null
  formattedAddress: string | null
  latitude: number | null
  longitude: number | null
  area: number | null
  startDate: string | null
  endDate: string | null
  status: ProjectStatus
  members: ProjectMemberSummaryDto[]
  client: ProjectMemberSummaryDto | null
}

/**
 * Returned by `GET /api/projects/{id}` (single-record read). Mirrors the backend `ProjectReadDto`;
 * structurally identical to {@link ProjectListDto}. Used by the edit form to prefill base fields.
 */
export type ProjectReadDto = ProjectListDto

/** Alias used by the DataTable/list UI (`DataTable<ProjectDto>`). */
export type ProjectDto = ProjectListDto

// --- API request types ---

/**
 * The "add a new client" invite fields. The client role is NOT selectable — the server always
 * fixes it to CLIENT — so there is no role field here (Requirement 8.4).
 */
export interface NewClientInput {
  name: string
  email: string
  phone?: string | null
  locale?: string | null
}

/**
 * A single team-member entry of {@link CreateProjectRequest.members}. Mirrors the backend
 * `ProjectMemberInput` (Requirement 2.4): the user to assign and the project role under which the
 * member is assigned (by default the user's company role).
 */
export interface ProjectMemberInput {
  userId: number
  projectRoleId: number
}

/**
 * The optional `client` section of {@link CreateProjectRequest} as sent on the wire. Mirrors the
 * backend `ClientBlock` (Requirement 2.5): either references an existing CLIENT user by id OR
 * describes a new client to create. No role identifier is accepted. (The UI toggle state is
 * modeled by {@link ClientBlockValue}; this is the serialized request shape.)
 */
export interface ClientBlock {
  existingClientUserId?: number | null
  newClient?: NewClientInput | null
}

/**
 * Request body of the custom project-creation endpoint `POST /api/projects`. Mirrors the backend
 * `CreateProjectRequest` (Requirements 2.2, 2.4, 2.5): base fields + team `members` + optional
 * `client` block. `status` defaults to `DRAFT` server-side when omitted.
 */
export interface CreateProjectRequest {
  name: string
  address?: string | null
  googlePlaceId?: string | null
  formattedAddress?: string | null
  latitude?: number | null
  longitude?: number | null
  area?: number | null
  startDate?: string | null
  endDate?: string | null
  status?: ProjectStatus | null
  members: ProjectMemberInput[]
  client?: ClientBlock | null
}

/**
 * Request body of the generic project update `PUT /api/projects/{id}`. Mirrors the backend
 * `ProjectUpdateRequest` (Requirement 3.3): base fields only (team/client are not editable through
 * the generic update).
 */
export interface ProjectUpdateRequest {
  name: string
  address?: string | null
  googlePlaceId?: string | null
  formattedAddress?: string | null
  latitude?: number | null
  longitude?: number | null
  area?: number | null
  startDate?: string | null
  endDate?: string | null
  status: ProjectStatus
}

// --- Client-block UI state (task 11.4) ---

/**
 * The client-block selection as modeled by the {@link ClientBlock} UI component: EITHER an existing
 * CLIENT user id OR a `newClient` invite payload, or `null` for "no client". This is the UI toggle
 * state; the {@link ClientBlock} request interface above is the serialized wire shape the form maps
 * this into on submit.
 */
export type ClientBlockValue =
  | { kind: 'existing'; existingClientUserId: number }
  | { kind: 'new'; newClient: NewClientInput }
  | null

/** Which side of the client toggle is active. */
export type ClientBlockMode = 'existing' | 'new'

/** Create/edit mode discriminator for the project form. */
export type ProjectFormMode = 'create' | 'edit'
