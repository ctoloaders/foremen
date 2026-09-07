/**
 * FOR-04-bugs — i18n used-key existence guard (Bug 3, Change F2).
 *
 * This guard test enforces two invariants for the translation catalogs:
 *
 *   (a) `pl.json` and `ru.json` have IDENTICAL flattened key sets, and every leaf
 *       value in both catalogs is a non-empty string (pl/ru parity — Req 3.2).
 *   (b) Every statically-referenced translation key in `src` — i.e. a single
 *       string literal passed to `t(...)` (or `i18nKey=`) that is a dotted key —
 *       exists in BOTH catalogs, so no raw dotted key can ever be rendered
 *       (Req 2.3).
 *
 * LIMITATION (documented intentionally): only STATIC single-literal dotted keys
 * can be verified. Many usages are dynamic/templated and are deliberately skipped
 * because their concrete key cannot be known statically, e.g.:
 *   - template literals:      t(`projects.status.${value}`)
 *   - variable / expression:  t(errors.name?.message ?? '')
 *   - runtime-computed keys:  t(getKey(row))
 * For pluralized calls such as t('dataTable.filters.summaryFilters', { count }),
 * only the BASE key is asserted to exist; i18next resolves the `_one/_few/_many/
 * _other` suffixes at runtime from that base key.
 *
 * Validates: Requirements 2.3, 3.2
 */
import { describe, expect, it } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

import plJson from '@/locales/pl.json'
import ruJson from '@/locales/ru.json'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SRC = path.resolve(HERE, '..')

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

/**
 * i18next resolves pluralized keys via `<base>_one/_few/_many/_other` suffixes.
 * A static `t('x.y')` reference is satisfied by EITHER an exact `x.y` leaf OR the
 * presence of any plural-suffixed variant `x.y_<category>`.
 */
const PLURAL_SUFFIXES = ['_zero', '_one', '_two', '_few', '_many', '_other']
function existsInCatalog(flat: Record<string, unknown>, key: string): boolean {
  if (key in flat) return true
  return PLURAL_SUFFIXES.some((s) => `${key}${s}` in flat)
}

/** Recursively collect source files, excluding tests and non-source dirs. */
function collectSourceFiles(dir: string, acc: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const full = path.join(dir, name)
    const st = statSync(full)
    if (st.isDirectory()) {
      if (name === '__tests__' || name === 'node_modules') continue
      collectSourceFiles(full, acc)
    } else if (
      /\.(ts|tsx)$/.test(name) &&
      !/\.test\.(ts|tsx)$/.test(name) &&
      !/\.d\.ts$/.test(name)
    ) {
      acc.push(full)
    }
  }
  return acc
}

/**
 * Scan `src` for static, single-literal dotted keys referenced via `t('literal')`,
 * `t("literal")`, or `i18nKey="literal"`. Dynamic/templated usages (template
 * literals, variables, expressions) are intentionally NOT matched — see file header.
 */
function collectStaticUsedKeys(): Map<string, string[]> {
  const files = collectSourceFiles(SRC)
  const patterns = [
    /\bt\(\s*'([a-zA-Z0-9_.]+)'/g,
    /\bt\(\s*"([a-zA-Z0-9_.]+)"/g,
    /i18nKey=\s*["']([a-zA-Z0-9_.]+)["']/g,
  ]
  const used = new Map<string, string[]>()
  for (const file of files) {
    const source = readFileSync(file, 'utf8')
    for (const re of patterns) {
      let m: RegExpExecArray | null
      while ((m = re.exec(source)) !== null) {
        const key = m[1]
        // Only dotted keys are catalog keys (ignore bare identifiers / namespaces).
        if (!key || !key.includes('.')) continue
        const rel = path.relative(SRC, file)
        const list = used.get(key) ?? []
        if (!list.includes(rel)) list.push(rel)
        used.set(key, list)
      }
    }
  }
  return used
}

// ---------------------------------------------------------------------------
// (a) pl/ru parity with non-empty string values (Req 3.2)
// ---------------------------------------------------------------------------
describe('i18n catalog parity (pl ≡ ru)', () => {
  it('pl.json and ru.json have identical flattened key sets', () => {
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
      ([, v]) => typeof v !== 'string' || (v as string).trim().length === 0,
    )
    const emptyRu = Object.entries(ruFlat).filter(
      ([, v]) => typeof v !== 'string' || (v as string).trim().length === 0,
    )
    expect(emptyPl.map(([k]) => k), 'empty/non-string pl values').toEqual([])
    expect(emptyRu.map(([k]) => k), 'empty/non-string ru values').toEqual([])
  })
})

// ---------------------------------------------------------------------------
// (b) every static used key exists in both catalogs (Req 2.3)
// ---------------------------------------------------------------------------
describe('i18n used-key existence guard', () => {
  const used = collectStaticUsedKeys()

  it('finds a non-trivial set of static used keys to guard', () => {
    // Sanity guard so the scan cannot silently pass by matching nothing.
    expect(used.size).toBeGreaterThan(50)
  })

  it('every static t("literal") dotted key exists in pl.json', () => {
    const missing: string[] = []
    for (const [key, files] of used) {
      if (!existsInCatalog(plFlat, key)) missing.push(`${key} (used in ${files.join(', ')})`)
    }
    expect(missing.sort(), `Used keys absent from pl.json:\n${missing.sort().join('\n')}`).toEqual(
      [],
    )
  })

  it('every static t("literal") dotted key exists in ru.json', () => {
    const missing: string[] = []
    for (const [key, files] of used) {
      if (!existsInCatalog(ruFlat, key)) missing.push(`${key} (used in ${files.join(', ')})`)
    }
    expect(missing.sort(), `Used keys absent from ru.json:\n${missing.sort().join('\n')}`).toEqual(
      [],
    )
  })
})
