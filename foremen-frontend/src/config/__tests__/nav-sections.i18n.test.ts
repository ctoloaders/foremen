import { describe, expect, it } from 'vitest'
import plJson from '@/locales/pl.json'
import ruJson from '@/locales/ru.json'

/**
 * i18n parity for the `nav.sections.*` subtree (FOR-04-15).
 *
 * Verifies that the two new section-title keys `nav.sections.catalog` and
 * `nav.sections.dictionaries` exist and are non-empty in both `pl.json` and
 * `ru.json`, that the `nav.sections.*` key set is identical across locales, and
 * that every leaf value is a non-empty string so no raw i18n key is ever
 * rendered by the navigation surfaces.
 *
 * Validates: Requirements 5.1, 5.3
 */

type LocaleCatalog = Record<string, unknown>

/** The `nav.sections` object for a locale, or an empty object if missing. */
function navSections(catalog: LocaleCatalog): Record<string, unknown> {
  const nav = (catalog.nav ?? {}) as Record<string, unknown>
  return (nav.sections ?? {}) as Record<string, unknown>
}

const plSections = navSections(plJson as LocaleCatalog)
const ruSections = navSections(ruJson as LocaleCatalog)

const plKeys = Object.keys(plSections).sort()
const ruKeys = Object.keys(ruSections).sort()

describe('nav.sections.* i18n parity between pl.json and ru.json (FOR-04-15)', () => {
  it('has a non-empty nav.sections.* subtree in both locales', () => {
    expect(plKeys.length).toBeGreaterThan(0)
    expect(ruKeys.length).toBeGreaterThan(0)
  })

  it('exposes an identical set of nav.sections.* keys in both locales (Req 5.3)', () => {
    const onlyInPl = plKeys.filter((k) => !(k in ruSections))
    const onlyInRu = ruKeys.filter((k) => !(k in plSections))

    expect(onlyInPl, `Keys present only in pl.json: ${onlyInPl.join(', ')}`).toEqual([])
    expect(onlyInRu, `Keys present only in ru.json: ${onlyInRu.join(', ')}`).toEqual([])
    expect(plKeys).toEqual(ruKeys)
  })

  it('defines nav.sections.catalog and nav.sections.dictionaries in both locales (Req 5.1)', () => {
    for (const key of ['catalog', 'dictionaries']) {
      expect(plSections[key], `Missing nav.sections.${key} in pl.json`).toBeTypeOf('string')
      expect(ruSections[key], `Missing nav.sections.${key} in ru.json`).toBeTypeOf('string')
    }
  })

  it('the new section titles are the expected localized strings (Req 5.1)', () => {
    expect(plSections.catalog).toBe('Katalog')
    expect(plSections.dictionaries).toBe('Słowniki')
    expect(ruSections.catalog).toBe('Каталог')
    expect(ruSections.dictionaries).toBe('Справочники')
  })

  it.each(plKeys)('pl.json nav.sections."%s" is a non-empty string (no raw key)', (key) => {
    const value = plSections[key]
    expect(typeof value, `Value for nav.sections.${key} in pl.json is not a string`).toBe('string')
    expect(
      (value as string).trim().length,
      `Empty value for nav.sections.${key} in pl.json`
    ).toBeGreaterThan(0)
  })

  it.each(ruKeys)('ru.json nav.sections."%s" is a non-empty string (no raw key)', (key) => {
    const value = ruSections[key]
    expect(typeof value, `Value for nav.sections.${key} in ru.json is not a string`).toBe('string')
    expect(
      (value as string).trim().length,
      `Empty value for nav.sections.${key} in ru.json`
    ).toBeGreaterThan(0)
  })
})
