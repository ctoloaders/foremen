// --- Shared pagination envelope ---

export interface PaginatedResponse<T> {
  content: T[]
  totalPages: number
  totalElements: number
  number: number // current page (0-indexed)
  size: number
}

// --- Measure source & value types ---

/**
 * Source flag for a room MeasureValue.
 *
 * `CALCULATED` — derived by the backend calculation engine from the room's
 * geometry. `MANUAL` — entered directly by the user with the geometry left
 * empty for that metric. Mirrors the backend `MeasureSource` enum
 * (`CALCULATED`, `MANUAL`, in that declaration order).
 */
export type MeasureSource = 'CALCULATED' | 'MANUAL'

/**
 * A metric value paired with its source flag, mirroring the backend
 * `MeasureValueDto { value, source }`. `value` may be null when the metric is
 * absent; `source` is null in that same case.
 */
export interface MeasureValue {
  value: number | null
  source: MeasureSource | null
}

// --- Geometry model ---

export type OpeningType = 'DOOR' | 'WINDOW'

/** A polygon vertex in room space (mathematical, y-up) coordinates. */
export interface Vertex {
  x: number
  y: number
}

/**
 * A door or window on a wall: a `type`, a positive integer `count` (szt), and
 * per-unit linear dimensions `height`/`width` (mb) from which area is derived.
 */
export interface Opening {
  type: OpeningType
  count: number
  height: number
  width: number
}

/**
 * An edge of the room polygon between two consecutive vertices. Carries the
 * per-wall "missing wall"/"missing finish" segment lengths (mb) and zero or
 * more openings.
 */
export interface Wall {
  wallGap?: number | null
  finishGap?: number | null
  openings: Opening[]
}

/**
 * Structured room geometry: an ordered `vertices` array and a `walls` array
 * where wall `i` connects vertex `i` to vertex `(i+1) mod n`. Persisted as JSON
 * on the backend and rendered as a read-only SVG on the frontend.
 */
export interface RoomGeometry {
  vertices: Vertex[]
  walls: Wall[]
}

// --- API Response Types ---

/**
 * Returned by GET /api/rooms (paginated). The referenced display values
 * (`projectName`, `roomTypeName`) are resolved server-side (roomTypeName is
 * locale-aware) so the table renders them directly. Each of `floorArea`,
 * `wallArea`, `perimeter`, `doorArea`, `windowArea` is a `{ value, source }`
 * MeasureValue.
 */
export interface RoomDto {
  id: number
  projectId: number
  projectName: string
  roomTypeId: number
  roomTypeName: string
  label: string | null
  ceilingHeight: number | null
  internalCorners: number | null
  doorCount: number | null
  windowCount: number | null
  doorHeight: number | null
  doorWidth: number | null
  windowHeight: number | null
  windowWidth: number | null
  wallGap: number | null
  finishGap: number | null
  geometry: RoomGeometry | null
  floorArea: MeasureValue
  wallArea: MeasureValue
  perimeter: MeasureValue
  doorArea: MeasureValue
  windowArea: MeasureValue
}

/**
 * Returned by GET /api/rooms/{id} (extended). Contains the flat FK ids, the
 * geometry, and the raw metric numerics + source flags. Used for the edit form.
 */
export interface RoomExtendedDto {
  id: number
  projectId: number
  roomTypeId: number
  label: string | null
  ceilingHeight: number | null
  internalCorners: number | null
  doorCount: number | null
  windowCount: number | null
  doorHeight: number | null
  doorWidth: number | null
  windowHeight: number | null
  windowWidth: number | null
  wallGap: number | null
  finishGap: number | null
  geometry: RoomGeometry | null
  floorArea: number | null
  wallArea: number | null
  perimeter: number | null
  doorArea: number | null
  windowArea: number | null
  floorAreaSource: MeasureSource | null
  wallAreaSource: MeasureSource | null
  perimeterSource: MeasureSource | null
  doorAreaSource: MeasureSource | null
  windowAreaSource: MeasureSource | null
}

// --- API Request Types ---

/**
 * Create payload. Manual metrics (`floorArea`/`wallArea`/`perimeter`/
 * `doorArea`/`windowArea`) are only honored when `geometry` is absent; when
 * geometry is present the backend overwrites them with calculated values.
 */
export interface RoomCreateRequest {
  projectId: number
  roomTypeId: number
  label?: string | null
  ceilingHeight?: number | null
  internalCorners?: number | null
  geometry?: RoomGeometry | null
  floorArea?: number | null
  wallArea?: number | null
  perimeter?: number | null
  doorArea?: number | null
  windowArea?: number | null
}

/** Update payload — same shape as create. */
export type RoomUpdateRequest = RoomCreateRequest

// --- UI State Types ---

export type RoomFormMode = 'create' | 'edit'
