import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'

import { RoomPlanPreview } from '@/features/rooms/components/RoomPlanPreview'
import type { RoomGeometry } from '@/features/rooms/types'

// The component only depends on i18next for its labels; return the key verbatim
// so assertions can target stable strings without a full i18n provider.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

/** A simple square room (4 vertices) with no openings. */
function makeSquareGeometry(): RoomGeometry {
  return {
    vertices: [
      { x: 0, y: 0 },
      { x: 10, y: 0 },
      { x: 10, y: 10 },
      { x: 0, y: 10 },
    ],
    walls: [
      { openings: [] },
      { openings: [] },
      { openings: [] },
      { openings: [] },
    ],
  }
}

describe('RoomPlanPreview', () => {
  it('renders a read-only SVG polygon from a valid geometry (>=3 vertices)', () => {
    render(<RoomPlanPreview geometry={makeSquareGeometry()} />)

    const svg = screen.getByTestId('room-plan-preview')
    expect(svg).toBeInTheDocument()
    expect(svg.tagName.toLowerCase()).toBe('svg')

    const polygon = screen.getByTestId('room-plan-polygon')
    expect(polygon).toBeInTheDocument()
    expect(polygon.tagName.toLowerCase()).toBe('polygon')

    // The polygon carries one "x,y" pair per vertex, projected into viewport space.
    const points = polygon.getAttribute('points') ?? ''
    const pairs = points.trim().split(/\s+/)
    expect(pairs).toHaveLength(4)
    pairs.forEach((pair) => {
      expect(pair).toMatch(/^-?\d+(\.\d+)?,-?\d+(\.\d+)?$/)
    })

    // No openings -> no door/window markers.
    expect(screen.queryByTestId('room-plan-opening-door')).not.toBeInTheDocument()
    expect(screen.queryByTestId('room-plan-opening-window')).not.toBeInTheDocument()
    expect(screen.queryByTestId('room-plan-empty')).not.toBeInTheDocument()
  })

  it('renders door and window opening markers on their walls', () => {
    const base = makeSquareGeometry()
    // Wall 0 gets a door, wall 1 gets a window.
    const geometry: RoomGeometry = {
      ...base,
      walls: [
        { openings: [{ type: 'DOOR', count: 1, height: 2, width: 0.9 }] },
        { openings: [{ type: 'WINDOW', count: 1, height: 1.2, width: 1.5 }] },
        { openings: [] },
        { openings: [] },
      ],
    }

    render(<RoomPlanPreview geometry={geometry} />)

    const door = screen.getByTestId('room-plan-opening-door')
    expect(door).toBeInTheDocument()
    expect(door.tagName.toLowerCase()).toBe('line')

    const windowMarker = screen.getByTestId('room-plan-opening-window')
    expect(windowMarker).toBeInTheDocument()
    expect(windowMarker.tagName.toLowerCase()).toBe('line')
  })

  it('renders the empty state for null geometry', () => {
    render(<RoomPlanPreview geometry={null} />)

    expect(screen.getByTestId('room-plan-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('room-plan-preview')).not.toBeInTheDocument()
  })

  it('renders the empty state for geometry with fewer than 3 vertices', () => {
    const geometry: RoomGeometry = {
      vertices: [
        { x: 0, y: 0 },
        { x: 10, y: 0 },
      ],
      walls: [{ openings: [] }],
    }

    render(<RoomPlanPreview geometry={geometry} />)

    expect(screen.getByTestId('room-plan-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('room-plan-polygon')).not.toBeInTheDocument()
  })
})
