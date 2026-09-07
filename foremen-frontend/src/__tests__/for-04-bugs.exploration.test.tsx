/**
 * FOR-04-bugs — Bug Condition exploration tests (Property 1 / Property 3 / Property 6-8).
 *
 * These tests intentionally encode the EXPECTED (fixed) behavior so they FAIL on the
 * current UNFIXED code. A failing assertion here is the SUCCESS criterion for the
 * exploration phase: each failure is a counterexample that confirms the corresponding
 * bug exists. DO NOT implement any fix in this file — fixes are separate tasks (3-6).
 *
 * Rationale for source-level assertions:
 *   jsdom does not run Tailwind v4 / PostCSS, so computed `background-color` for a
 *   `bg-popover` element is not reliably testable. Instead we assert the *root cause*
 *   at the source level — e.g. that `index.css` defines the `--popover` token and the
 *   `@theme` mapping, that the catalogs contain the used i18n keys, and that the form
 *   source uses the intended shared control rather than a native element. These are
 *   deterministic, dependency-free, and genuinely fail today.
 *
 * Bug coverage:
 *   Bugs 1, 2  — transparent popovers (undefined --popover / --color-popover)
 *   Bug 3      — missing i18n keys (used in code, absent from pl.json/ru.json)
 *   Bug 5      — project form uses native <input type="date"> not a Calendar picker
 *   Bug 7      — room / work-item forms use native <select> not an async combobox
 *   Bug 8      — decimal input drops the ',' separator (Number(',') semantics)
 *   Bug 9      — wall-delete + manual area produces a payload with NULLed manual areas
 *   Bug 10     — work-category options fetched with sort=name,asc not orderNo,asc
 *   Bug 11     — projects list members/client columns are not ColumnConfig.reference filters
 *
 * Validates: Requirements 1.1, 1 (defect).1, 1.3, 1.5, 1.7, 1.8, 1.9, 1.10, 1.11
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

/** Resolve a dotted i18n key against a nested catalog; returns the leaf value or undefined. */
function getKey(catalog: unknown, dotted: string): unknown {
  return dotted
    .split('.')
    .reduce<unknown>(
      (acc, part) =>
        acc != null && typeof acc === 'object'
          ? (acc as Record<string, unknown>)[part]
          : undefined,
      catalog,
    )
}

const pl = plJson as Record<string, unknown>
const ru = ruJson as Record<string, unknown>

// ---------------------------------------------------------------------------
// Bugs 1 + 2 — transparent popovers (shared root cause: undefined --popover)
// ---------------------------------------------------------------------------
describe('Bugs 1,2 — popover theme variables (expected FAIL on unfixed code)', () => {
  const indexCss = readSrc('index.css')

  it('SelectContent/PopoverContent consume bg-popover text-popover-foreground', () => {
    // Precondition sanity: the surfaces really do rely on the popover token.
    expect(readSrc('components/ui/select.tsx')).toContain('bg-popover')
    expect(readSrc('components/ui/popover.tsx')).toContain('bg-popover')
  })

  it('index.css defines --popover and --popover-foreground in :root', () => {
    // FAILS today: only --card* is defined, --popover* is missing → transparent panels.
    expect(indexCss, 'index.css should define --popover').toMatch(/--popover\s*:/)
    expect(indexCss, 'index.css should define --popover-foreground').toMatch(
      /--popover-foreground\s*:/,
    )
  })

  it('index.css maps --color-popover / --color-popover-foreground in @theme', () => {
    // FAILS today: @theme maps --color-card* but not --color-popover*.
    expect(indexCss, 'index.css @theme should map --color-popover').toMatch(
      /--color-popover\s*:/,
    )
    expect(indexCss, 'index.css @theme should map --color-popover-foreground').toMatch(
      /--color-popover-foreground\s*:/,
    )
  })
})

// ---------------------------------------------------------------------------
// Bug 3 — missing i18n keys (used in code, absent from catalogs)
// ---------------------------------------------------------------------------
describe('Bug 3 — used i18n keys must exist in both catalogs (expected FAIL)', () => {
  // Confirmed-missing keys referenced statically in the project/data-table code.
  const confirmedMissing = [
    'projects.form.address',
    'projects.form.team.label',
    'projects.form.team.hint',
    'projects.form.team.empty',
    'projects.form.team.placeholder',
    'projects.form.team.search',
    'projects.form.team.selectedCount',
    'projects.toast.createError',
    'projects.toast.updateError',
    'projects.toast.deleteError',
    'dataTable.filters.summaryFilters',
    'dataTable.filters.summarySorts',
  ]

  it.each(confirmedMissing)('key "%s" resolves in pl.json', (key) => {
    // FAILS today: key is undefined in pl.json.
    const value = getKey(pl, key)
    expect(typeof value, `pl.json missing "${key}"`).toBe('string')
    expect((value as string)?.trim().length ?? 0).toBeGreaterThan(0)
  })

  it.each(confirmedMissing)('key "%s" resolves in ru.json', (key) => {
    // FAILS today: key is undefined in ru.json.
    const value = getKey(ru, key)
    expect(typeof value, `ru.json missing "${key}"`).toBe('string')
    expect((value as string)?.trim().length ?? 0).toBeGreaterThan(0)
  })

  it('generic used-key existence guard: every static t("literal") in src exists in both catalogs', () => {
    // Scan the project/data-table sources for static single-quoted t('literal') usages
    // and assert each dotted literal key exists in both catalogs. FAILS today because
    // the confirmed-missing keys above are referenced but absent.
    const filesToScan = [
      'features/projects/components/ProjectFormSheet.tsx',
      'features/projects/components/ProjectsList.tsx',
    ]
    const tLiteral = /\bt\(\s*'([a-zA-Z0-9_.]+)'/g
    const missing = new Set<string>()
    for (const rel of filesToScan) {
      const source = readSrc(rel)
      let m: RegExpExecArray | null
      while ((m = tLiteral.exec(source)) !== null) {
        const key = m[1]
        // Only dotted keys are catalog keys (ignore bare identifiers / dynamic prefixes).
        // The `!key ||` guard also satisfies strict `noUncheckedIndexedAccess` (m[1] is
        // `string | undefined` under tsc strictness).
        if (!key || !key.includes('.')) continue
        if (getKey(pl, key) === undefined || getKey(ru, key) === undefined) {
          missing.add(key)
        }
      }
    }
    expect([...missing].sort(), `Used t() keys absent from a catalog: ${[...missing].sort().join(', ')}`).toEqual([])
  })
})

// ---------------------------------------------------------------------------
// Bug 5 — project form date picker (should be Calendar-based, not native date input)
// ---------------------------------------------------------------------------
describe('Bug 5 — project form uses a Calendar-based date picker (expected FAIL)', () => {
  const src = readSrc('features/projects/components/ProjectFormSheet.tsx')

  it('does not use native <input type="date"> for start/end dates', () => {
    // FAILS today: both start and end dates are native date inputs.
    expect(src, 'ProjectFormSheet should not use native date inputs').not.toMatch(
      /type="date"/,
    )
  })

  it('uses the shared Calendar-based DatePicker component', () => {
    // FAILS today: no DatePicker/Calendar wiring exists yet.
    expect(src).toMatch(/DatePicker|Calendar/)
  })
})

// ---------------------------------------------------------------------------
// Bug 7 — room / work-item entity fields should be async comboboxes, not native <select>
// ---------------------------------------------------------------------------
describe('Bug 7 — entity selects are async comboboxes, not native <select> (expected FAIL)', () => {
  it('RoomFormSheet project/roomType are not native <select> and use an async entity select', () => {
    const src = readSrc('features/rooms/components/RoomFormSheet.tsx')
    // FAILS today: renders native <select ...> populated from one-shot useReferenceOptions(size=200).
    expect(src, 'RoomFormSheet should not render native <select>').not.toMatch(/<select\b/)
    expect(src, 'RoomFormSheet should use an async entity select').toMatch(
      /AsyncEntitySelect/,
    )
  })

  it('WorkItemFormSheet workCategory/unit are not native <select> and use an async entity select', () => {
    const src = readSrc('features/work-catalog/components/WorkItemFormSheet.tsx')
    // FAILS today: renders native <select ...> for workCategoryId and unitId.
    expect(src, 'WorkItemFormSheet should not render native <select>').not.toMatch(/<select\b/)
    expect(src, 'WorkItemFormSheet should use an async entity select').toMatch(
      /AsyncEntitySelect/,
    )
  })
})

// ---------------------------------------------------------------------------
// Bug 8 — decimal separator: a comma-typed value must normalize to a dot before coercion
// ---------------------------------------------------------------------------
describe('Bug 8 — decimal comma separator is accepted/normalized (expected FAIL)', () => {
  it('a shared decimal NumberInput exists and normalizes "," to "."', () => {
    // FAILS today: there is no shared number-input component with normalization.
    let src = ''
    try {
      src = readSrc('components/ui/number-input.tsx')
    } catch {
      src = ''
    }
    expect(src.length, 'expected src/components/ui/number-input.tsx to exist').toBeGreaterThan(0)
    // The intended component normalizes comma → dot before emitting.
    expect(src).toMatch(/replace\(\s*\/?,/)
  })

  it('the room schema normalizes comma decimals before coercion (12,5 → 12.5)', () => {
    // Demonstrates the underlying defect: native Number()/z.coerce.number() drops the comma.
    // 12,5 typed by a user becomes NaN under the current coercion, so the value is lost.
    const raw = '12,5'
    expect(Number(raw), 'Number("12,5") is NaN — the intended 12.5 is lost').toBeNaN()

    // The schema currently applies no comma-normalization preprocessing, so it cannot
    // parse a comma decimal. FAILS today (there is no `,`-aware preprocess in room-schema).
    const schemaSrc = readSrc('features/rooms/schemas/room-schema.ts')
    expect(
      schemaSrc,
      'room-schema should normalize comma decimals before coercion',
    ).toMatch(/replace\(\s*\/?,/)
  })
})

// ---------------------------------------------------------------------------
// Bug 9 — wall-delete + manual area: manual metrics must survive when a wall remains
//
// After the fix (task 5.4) these assertions encode the FIXED manual-override behavior
// and PASS: RoomFormSheet carries an explicit `manualOverride` flag decoupled from wall
// count, and the submit derives geometry from that flag (dropping geometry so the
// user-entered manual metrics are persisted) rather than nulling manual metrics whenever
// a wall remains.
// ---------------------------------------------------------------------------
describe('Bug 9 — manual override persists manual areas when walls remain (fixed)', () => {
  const src = readSrc('features/rooms/components/RoomFormSheet.tsx')

  it('exposes an explicit manualOverride flag decoupled from wall count', () => {
    // The fix introduces a dedicated manual-override state so entering areas manually is
    // no longer tied to having zero walls.
    expect(
      src,
      'RoomFormSheet should carry an explicit manualOverride flag',
    ).toMatch(/manualOverride/)
    // A UI affordance to enable manual entry (localized toggle) must exist.
    expect(
      src,
      'RoomFormSheet should render a manual-override toggle',
    ).toMatch(/rooms\.form\.manualOverride/)
  })

  it('submit drops geometry in manual mode so manual metrics are sent, not nulled', () => {
    // Geometry is derived from the manual-mode flag (manualOverride || no walls) rather
    // than blindly from wall presence, so when the user overrides, geometry is null and
    // the manual metrics flow through to the payload.
    expect(
      src,
      'onSubmit should derive geometry from the manual-metrics flag',
    ).toMatch(/useManualMetrics\s*\?\s*null\s*:\s*wallsToGeometry/)
    // The manual metrics are submitted when geometry is null (manual mode).
    expect(
      src,
      'manual metrics should be submitted (not forced null) in manual mode',
    ).toMatch(/floorArea:\s*geometry\s*\?\s*null\s*:\s*\(values\.floorArea/)
  })
})

// ---------------------------------------------------------------------------
// Bug 10 — work-category dropdown must be ordered by orderNo, not name
// ---------------------------------------------------------------------------
describe('Bug 10 — work-category options ordered by orderNo (expected FAIL)', () => {
  const src = readSrc('features/work-catalog/components/WorkItemFormSheet.tsx')

  it('work categories are fetched sorted by orderNo, not by name', () => {
    // FAILS today: options fetched with `sort=name,asc`; the intended sort is orderNo asc.
    expect(src, 'work categories still fetched with sort=name,asc').not.toMatch(
      /sort=name,asc/,
    )
    expect(src, 'work categories should be fetched with sort=orderNo,asc').toMatch(
      /orderNo/,
    )
  })
})

// ---------------------------------------------------------------------------
// Bug 11 — projects list members/client are ColumnConfig.reference column filters
// ---------------------------------------------------------------------------
describe('Bug 11 — projects list members/client are column filters (expected FAIL)', () => {
  const src = readSrc('features/projects/components/ProjectsList.tsx')

  it('members/client columns are filterable via ColumnConfig.reference', () => {
    // Locate the members and client column blocks and assert they are filterable and carry
    // a `reference` descriptor. FAILS today: both are `filterable: false` with no reference.
    const membersBlock = src.slice(src.indexOf("field: 'members'"))
    const clientBlock = src.slice(src.indexOf("field: 'client'"))

    expect(membersBlock, 'members column is not filterable').not.toMatch(
      /filterable:\s*false/,
    )
    expect(membersBlock, 'members column lacks a reference descriptor').toMatch(
      /reference:/,
    )
    expect(clientBlock, 'client column is not filterable').not.toMatch(
      /filterable:\s*false/,
    )
    expect(clientBlock, 'client column lacks a reference descriptor').toMatch(/reference:/)
  })

  it('the standalone toolbar ProjectMembersFilter/ProjectClientFilter panels are removed', () => {
    // FAILS today: the bespoke toolbar filter panels + manual composeQuery/invalidateQueries
    // plumbing are still present instead of using the built-in column-filter mechanism.
    expect(src, 'ProjectMembersFilter toolbar panel still rendered').not.toMatch(
      /<ProjectMembersFilter\b/,
    )
    expect(src, 'ProjectClientFilter toolbar panel still rendered').not.toMatch(
      /<ProjectClientFilter\b/,
    )
    expect(src, 'manual composeQuery plumbing still present').not.toMatch(/composeQuery\(/)
  })
})
