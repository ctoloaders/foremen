import { useTranslation } from 'react-i18next'

import { cn } from '@/lib/utils'
import type { Opening, RoomGeometry, Vertex } from '../types'

interface RoomPlanPreviewProps {
  /** The stored room geometry, or null/undefined when no geometry is drawn. */
  geometry?: RoomGeometry | null
  /** Optional extra classes for the wrapping SVG element. */
  className?: string
  /**
   * Nominal size (px) of the square SVG viewport the polygon is scaled to fit.
   * The polygon is uniformly scaled and centered inside this box. Defaults to 240.
   */
  size?: number
}

// --- Layout constants ---------------------------------------------------------

/** Inner padding (in viewBox units) so the polygon never touches the edges. */
const PADDING = 16
/** Half-length (in viewBox units) of the marker drawn across a wall opening. */
const OPENING_HALF_LENGTH = 10

// --- Geometry helpers ---------------------------------------------------------

interface Bounds {
  minX: number
  minY: number
  width: number
  height: number
}

/** Bounding box of the polygon vertices. */
function computeBounds(vertices: Vertex[]): Bounds {
  const xs = vertices.map((v) => v.x)
  const ys = vertices.map((v) => v.y)
  const minX = Math.min(...xs)
  const minY = Math.min(...ys)
  return {
    minX,
    minY,
    width: Math.max(...xs) - minX,
    height: Math.max(...ys) - minY,
  }
}

/**
 * Maps room-space vertices into the SVG viewport: uniform scale to fit inside
 * `size - 2*PADDING`, centered, and Y-flipped so a mathematical (y-up) polygon
 * renders the natural way up in SVG's (y-down) coordinate space.
 */
function projectVertices(vertices: Vertex[], size: number): Vertex[] {
  const bounds = computeBounds(vertices)
  const usable = size - PADDING * 2
  // Guard against a degenerate (zero-width/height) bounding box.
  const scale = Math.min(
    bounds.width > 0 ? usable / bounds.width : usable,
    bounds.height > 0 ? usable / bounds.height : usable,
  )
  // Center the scaled polygon within the usable area.
  const offsetX = (usable - bounds.width * scale) / 2
  const offsetY = (usable - bounds.height * scale) / 2

  return vertices.map((v) => ({
    x: PADDING + offsetX + (v.x - bounds.minX) * scale,
    // Flip Y: subtract from height so larger y-values render higher up.
    y: PADDING + offsetY + (bounds.height - (v.y - bounds.minY)) * scale,
  }))
}

interface OpeningMarker {
  key: string
  x1: number
  y1: number
  x2: number
  y2: number
  type: Opening['type']
}

/**
 * Builds one marker segment per wall that carries openings. The marker is drawn
 * at the midpoint of the wall edge, perpendicular offset kept minimal: it lies
 * along the wall so it visually "cuts" the edge where the door/window sits.
 * Walls with no openings produce no marker.
 */
function buildOpeningMarkers(points: Vertex[], geometry: RoomGeometry): OpeningMarker[] {
  const markers: OpeningMarker[] = []
  const n = points.length

  geometry.walls.forEach((wall, wallIndex) => {
    if (!wall.openings || wall.openings.length === 0) return
    // Wall i connects vertex i -> (i+1) mod n. Skip if either endpoint is
    // missing (geometry with more walls than vertices, defensively).
    if (wallIndex >= n) return
    const a = points[wallIndex]
    const b = points[(wallIndex + 1) % n]
    if (!a || !b) return
    const midX = (a.x + b.x) / 2
    const midY = (a.y + b.y) / 2
    // Unit vector along the wall edge.
    const dx = b.x - a.x
    const dy = b.y - a.y
    const len = Math.hypot(dx, dy) || 1
    const ux = dx / len
    const uy = dy / len
    // The dominant opening type on this wall drives the marker style; each
    // opening gets its own marker centered on the wall midpoint but nudged so
    // multiple openings on one wall remain distinguishable.
    wall.openings.forEach((opening, openingIndex) => {
      const nudge = (openingIndex - (wall.openings.length - 1) / 2) * OPENING_HALF_LENGTH
      const cx = midX + ux * nudge
      const cy = midY + uy * nudge
      markers.push({
        key: `w${wallIndex}-o${openingIndex}`,
        x1: cx - ux * OPENING_HALF_LENGTH,
        y1: cy - uy * OPENING_HALF_LENGTH,
        x2: cx + ux * OPENING_HALF_LENGTH,
        y2: cy + uy * OPENING_HALF_LENGTH,
        type: opening.type,
      })
    })
  })

  return markers
}

/**
 * Read-only SVG plan of a room polygon and its per-wall openings, rendered from
 * the stored {@link RoomGeometry} JSON (FOR-04-14 Requirement 8.6). The polygon
 * is drawn from `geometry.vertices`; openings are marked on their wall edges
 * (doors and windows use distinct stroke styling). This preview has no editing
 * behavior — the interactive drawing editor is deferred to FOR-05.
 *
 * Renders a localized empty state when the geometry is null/undefined or has
 * fewer than 3 vertices (not a valid polygon).
 */
export function RoomPlanPreview({
  geometry,
  className,
  size = 240,
}: Readonly<RoomPlanPreviewProps>) {
  const { t } = useTranslation()

  const vertices = geometry?.vertices ?? []

  // A valid polygon needs at least 3 vertices; anything less renders as empty.
  if (vertices.length < 3) {
    return (
      <div
        className={cn(
          'flex h-[240px] w-full items-center justify-center rounded-md border border-dashed text-sm text-muted-foreground',
          className,
        )}
        data-testid="room-plan-empty"
      >
        {t('rooms.plan.empty')}
      </div>
    )
  }

  const points = projectVertices(vertices, size)
  const polygonPoints = points.map((p) => `${p.x},${p.y}`).join(' ')
  const markers = buildOpeningMarkers(points, geometry as RoomGeometry)

  return (
    <svg
      role="img"
      aria-label={t('rooms.plan.title')}
      viewBox={`0 0 ${size} ${size}`}
      className={cn('h-auto w-full rounded-md border bg-background', className)}
      data-testid="room-plan-preview"
    >
      <title>{t('rooms.plan.title')}</title>

      {/* Room outline */}
      <polygon
        points={polygonPoints}
        className="fill-muted/40 stroke-foreground"
        strokeWidth={2}
        strokeLinejoin="round"
        data-testid="room-plan-polygon"
      />

      {/* Vertex dots */}
      {points.map((p) => (
        <circle
          key={`v-${p.x}-${p.y}`}
          cx={p.x}
          cy={p.y}
          r={2.5}
          className="fill-foreground"
        />
      ))}

      {/* Openings on wall edges: doors solid, windows dashed */}
      {markers.map((m) => (
        <line
          key={m.key}
          x1={m.x1}
          y1={m.y1}
          x2={m.x2}
          y2={m.y2}
          strokeWidth={4}
          strokeLinecap="round"
          className={cn(
            m.type === 'DOOR' ? 'stroke-primary' : 'stroke-[#3b82f6]',
          )}
          strokeDasharray={m.type === 'WINDOW' ? '3 3' : undefined}
          data-testid={`room-plan-opening-${m.type.toLowerCase()}`}
        />
      ))}
    </svg>
  )
}
