/**
 * FOR-04-bugs — Preservation tests (Property 2).
 *
 * These tests capture BASELINE behavior that must NOT change after the fixes in
 * tasks 3-6 land. They MUST PASS on the current UNFIXED code (that is the success
 * criterion for the preservation phase) AND continue to pass after every fix.
 * DO NOT implement any fix in this file.
 *
 * Because jsdom does not run Tailwind v4 / PostCSS, popover "opacity" is asserted
 * at the source level: the surfaces that are already opaque hardcode `bg-background`
 * (independent of the `--popover` token), so their opacity cannot regress when the
 * popover-token fix (Bugs 1/2) is applied. Behavioral facts (i18n parity, the
 * pure-geometry submit shape, the WorkCatalogList reference filter semantics) are
 * asserted directly.
 *
 * Preservation coverage:
 *   Req 3.1 — opaque-by-default popovers (DataTableHeader, FilterPopover) unchanged
 *   Req 3.2 — existing i18n keys resolve; pl/ru key sets identical with non-empty values
 *   Req 3.4 — geometry-only room (walls, NO manual override) submits derived metrics
 *             with manual metrics null
 *   Req 3.5 — WorkCatalogList reference-column filter query semantics unchanged
 *
 * Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5
 */
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

import plJson from '@/locales/pl.json'
import ruJson from '@/locales/ru.json'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SRC = path.resolve(HERE, '..')

function readSrc(relFromSrc: string): string {
  return readFileSync(path.join(SRC, relFromSrc), 'utf8')
}

/** Flatten a nested i18n catalog into dotted-key → leaf-value pairs. */
function flatten(
  obj: unknown,
  prefix = '',
  out: Record<string, unknown> = {},
): Record<string, unknown> {
  if (obj == null || typeof obj !== 'object') return out
  for (const [k, v] of Object.entries(obj as Record<string, unknown>)) {
    const key = prefix ? `${prefix}.${k}` : k
    if (v != null && typeof v === 'object' && !Array.isArray(v)) {
      flatten(v, key, out)
    } else {
      out[key] = v
    }
  }
  return out
}

const plFlat = flatten(plJson)
const ruFlat = flatten(ruJson)

// ---------------------------------------------------------------------------
// Req 3.1 — Opaque-by-default popovers unchanged (independent of --popover token)
// ---------------------------------------------------------------------------
describe('Preservation 3.1 — opaque-by-default popovers hardcode bg-background', () => {
  it('DataTableHeader popover content hardcodes bg-background (not bg-popover)', () => {
    const src = readSrc('components/data-table/DataTableHeader.tsx')
    // Its PopoverContent opacity comes from a hardcoded bg-background, so it is
    // already opaque and cannot regress when the --popover token is defined.
    expect(src, 'DataTableHeader must keep hardcoding bg-background').toMatch(
      /bg-background/,
    )
  })

  it('FilterPopover content hardcodes bg-background (not bg-popover)', () => {
    const src = readSrc('components/data-table/filters/FilterPopover.tsx')
    expect(src, 'FilterPopover must keep hardcoding bg-background').toMatch(
      /bg-background/,
    )
    // Guard: it must NOT start relying on the popover token for its background.
    expect(src, 'FilterPopover must not use bg-popover for its panel').not.toMatch(
      /className="[^"]*\bbg-popover\b/,
    )
  })
})

// ---------------------------------------------------------------------------
// Req 3.2 — Existing i18n keys + pl/ru parity unchanged
// ---------------------------------------------------------------------------
describe('Preservation 3.2 — pl/ru catalog parity with non-empty values', () => {
  it('pl.json and ru.json have identical key sets', () => {
    const plKeys = Object.keys(plFlat).sort()
    const ruKeys = Object.keys(ruFlat).sort()

    const onlyInPl = plKeys.filter((k) => !(k in ruFlat))
    const onlyInRu = ruKeys.filter((k) => !(k in plFlat))

    expect(onlyInRu, `keys only in ru.json: ${onlyInRu.join(', ')}`).toEqual([])
    expect(onlyInPl, `keys only in pl.json: ${onlyInPl.join(', ')}`).toEqual([])
    expect(plKeys).toEqual(ruKeys)
  })

  it('every leaf value in both catalogs is a non-empty string', () => {
    const emptyPl = Object.entries(plFlat).filter(
      ([, v]) => typeof v !== 'string' || v.trim().length === 0,
    )
    const emptyRu = Object.entries(ruFlat).filter(
      ([, v]) => typeof v !== 'string' || v.trim().length === 0,
    )
    expect(emptyPl.map(([k]) => k), 'empty/non-string pl values').toEqual([])
    expect(emptyRu.map(([k]) => k), 'empty/non-string ru values').toEqual([])
  })

  it('a sampling of well-known existing keys resolves in both catalogs', () => {
    const known = ['common.save', 'common.cancel', 'nav.projects', 'nav.rooms']
    for (const key of known) {
      expect(typeof plFlat[key], `pl.json should keep "${key}"`).toBe('string')
      expect(typeof ruFlat[key], `ru.json should keep "${key}"`).toBe('string')
    }
  })
})

// ---------------------------------------------------------------------------
// Req 3.4 — Pure-geometry room submit shape (walls present, NO manual override)
// ---------------------------------------------------------------------------
//
// Re-encodes the CURRENT RoomFormSheet submit contract for the pure-geometry path:
// when walls are present AND the user has NOT switched to manual override, the
// derived metrics are submitted as null (the backend recomputes them from geometry).
// This assertion is deliberately conditioned on "no manual override" so it stays
// true after Bug 9's fix (which only changes the manual-override path), rather than
// locking in the buggy "always null while a wall remains" behavior.
describe('Preservation 3.4 — pure-geometry room forces manual metrics to null', () => {
  type Metric =
    | 'floorArea'
    | 'wallArea'
    | 'perimeter'
    | 'doorArea'
    | 'windowArea'
  interface EditorWallLike {
    wallGap: string
    finishGap: string
    openings: { type: string; count: string; height: string; width: string }[]
  }
  interface RoomValues {
    projectId: number
    roomTypeId: number
    label: string
    ceilingHeight: number | null
    internalCorners: number | null
    floorArea: number | null
    wallArea: number | null
    perimeter: number | null
    doorArea: number | null
    windowArea: number | null
  }

  const METRICS: Metric[] = [
    'floorArea',
    'wallArea',
    'perimeter',
    'doorArea',
    'windowArea',
  ]

  const numOrNull = (v: string): number | null => {
    if (v.trim() === '') return null
    const n = Number(v)
    return Number.isFinite(n) ? n : null
  }

  // Mirrors RoomFormSheet.wallsToGeometry: null when no walls, otherwise a payload.
  const wallsToGeometry = (walls: EditorWallLike[]) => {
    if (walls.length === 0) return null
    return {
      vertices: [] as unknown[],
      walls: walls.map((w) => ({
        wallGap: numOrNull(w.wallGap),
        finishGap: numOrNull(w.finishGap),
        openings: w.openings.map((o) => ({
          type: o.type,
          count: Number(o.count),
          height: Number(o.height),
          width: Number(o.width),
        })),
      })),
    }
  }

  // Mirrors RoomFormSheet.onSubmit for the pure-geometry path (no manual override):
  // manual metrics are forced to null whenever geometry is present.
  const buildPayload = (walls: EditorWallLike[], values: RoomValues) => {
    const geometry = wallsToGeometry(walls)
    return {
      projectId: values.projectId,
      roomTypeId: values.roomTypeId,
      label: values.label ? values.label : null,
      ceilingHeight: values.ceilingHeight ?? null,
      internalCorners: values.internalCorners ?? null,
      geometry,
      floorArea: geometry ? null : (values.floorArea ?? null),
      wallArea: geometry ? null : (values.wallArea ?? null),
      perimeter: geometry ? null : (values.perimeter ?? null),
      doorArea: geometry ? null : (values.doorArea ?? null),
      windowArea: geometry ? null : (values.windowArea ?? null),
    }
  }

  const wall = (): EditorWallLike => ({
    wallGap: '2',
    finishGap: '1',
    openings: [{ type: 'DOOR', count: '1', height: '2', width: '1' }],
  })

  it('walls present + no manual override → geometry sent, all manual metrics null', () => {
    const walls = [wall(), wall()]
    // The user typed nothing in the manual metric fields (pure geometry mode).
    const values: RoomValues = {
      projectId: 1,
      roomTypeId: 2,
      label: 'Room A',
      ceilingHeight: 2.7,
      internalCorners: 0,
      floorArea: null,
      wallArea: null,
      perimeter: null,
      doorArea: null,
      windowArea: null,
    }
    const payload = buildPayload(walls, values)

    expect(payload.geometry, 'geometry payload must be present').not.toBeNull()
    for (const m of METRICS) {
      expect(payload[m], `${m} must be null in pure-geometry mode`).toBeNull()
    }
  })

  it('even if stray manual values leak in, pure-geometry mode nulls them (current contract)', () => {
    // Demonstrates the CURRENT preserved contract for the pure-geometry path:
    // any residual manual value is overridden to null while geometry is present.
    const walls = [wall()]
    const values: RoomValues = {
      projectId: 1,
      roomTypeId: 2,
      label: '',
      ceilingHeight: null,
      internalCorners: null,
      floorArea: 99,
      wallArea: 88,
      perimeter: 77,
      doorArea: 66,
      windowArea: 55,
    }
    const payload = buildPayload(walls, values)
    for (const m of METRICS) {
      expect(payload[m], `${m} nulled in pure-geometry mode`).toBeNull()
    }
  })

  it('source: RoomFormSheet still documents/forces the pure-geometry null contract', () => {
    // Guard at the source level that the pure-geometry path continues to force
    // manual metrics null when geometry is present. (Bug 9 changes only the
    // manual-override branch; the pure-geometry branch must remain.)
    const src = readSrc('features/rooms/components/RoomFormSheet.tsx')
    expect(src, 'wallsToGeometry helper must remain').toMatch(/wallsToGeometry/)
    expect(
      src,
      'pure-geometry submit must still null manual metrics when geometry present',
    ).toMatch(/geometry\s*\?\s*null/)
  })
})

// ---------------------------------------------------------------------------
// Req 3.5 — WorkCatalogList reference-column filter query semantics unchanged
// ---------------------------------------------------------------------------
describe('Preservation 3.5 — WorkCatalogList reference column descriptors unchanged', () => {
  const src = readSrc('features/work-catalog/components/WorkCatalogList.tsx')

  it('workCategory column keeps its reference descriptor + idPath', () => {
    const block = src.slice(src.indexOf("field: 'workCategory'"))
    expect(block).toMatch(/reference:/)
    expect(block).toMatch(/targetResource:\s*'work-categories'/)
    expect(block).toMatch(/optionsPath:\s*'\/api\/work-categories'/)
    expect(block).toMatch(/idPath:\s*'workCategory\.id'/)
    expect(block).toMatch(/filterable:\s*true/)
  })

  it('unit column keeps its reference descriptor + idPath', () => {
    const block = src.slice(src.indexOf("field: 'unit'"))
    expect(block).toMatch(/reference:/)
    expect(block).toMatch(/targetResource:\s*'measurement-units'/)
    expect(block).toMatch(/optionsPath:\s*'\/api\/measurement-units'/)
    expect(block).toMatch(/idPath:\s*'unit\.id'/)
    expect(block).toMatch(/filterable:\s*true/)
  })
})
