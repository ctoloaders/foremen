import type {
  ColorScheme,
  DisplayPreferencesResponse,
  FontSize,
  ResolvedMode,
  ThemeMode,
  ThemePreferences,
} from '@/features/settings/types'
import { COLOR_PRESETS, FONT_SIZE_MAP } from '@/lib/theme-presets'

/**
 * Valid values for each preference field, used for validation.
 */
const VALID_THEME_MODES: ThemeMode[] = ['dark', 'light', 'system']
const VALID_COLOR_SCHEMES: ColorScheme[] = [
  'zinc',
  'slate',
  'stone',
  'gray',
  'neutral',
  'blue',
  'green',
  'orange',
  'red',
]
const VALID_FONT_SIZES: FontSize[] = ['sm', 'default', 'lg', 'xl']

/** Default preferences used as fallback for invalid/missing values. */
const DEFAULT_PREFERENCES: ThemePreferences = {
  themeMode: 'system',
  colorScheme: 'zinc',
  fontSize: 'default',
}

/**
 * Adds or removes the `dark` class on `document.documentElement`
 * based on the resolved mode.
 */
export function applyThemeMode(
  _mode: ThemeMode,
  resolvedMode: ResolvedMode,
): void {
  if (resolvedMode === 'dark') {
    document.documentElement.classList.add('dark')
  } else {
    document.documentElement.classList.remove('dark')
  }
}

/**
 * Sets --primary, --primary-foreground, --accent, --accent-foreground, --ring
 * CSS custom properties on `document.documentElement` from the selected preset.
 */
export function applyColorScheme(
  scheme: ColorScheme,
  resolvedMode: ResolvedMode,
): void {
  // Fallback to first preset (zinc) if scheme not found
  const preset =
    COLOR_PRESETS.find((p) => p.name === scheme) ??
    (COLOR_PRESETS[0] as (typeof COLOR_PRESETS)[number])
  const variant = resolvedMode === 'dark' ? preset.dark : preset.light

  const el = document.documentElement
  el.style.setProperty('--primary', variant.primary)
  el.style.setProperty('--primary-foreground', variant.primaryForeground)
  el.style.setProperty('--accent', variant.accent)
  el.style.setProperty('--accent-foreground', variant.accentForeground)
  el.style.setProperty('--ring', variant.ring)
}

/**
 * Sets --font-size-base on `document.documentElement` to the corresponding pixel value.
 */
export function applyFontSize(size: FontSize): void {
  const value = FONT_SIZE_MAP[size] ?? FONT_SIZE_MAP['default']
  document.documentElement.style.setProperty('--font-size-base', value)
}

/**
 * Resolves a ThemeMode to a concrete dark/light ResolvedMode.
 * - "dark" → "dark"
 * - "light" → "light"
 * - "system" → check `matchMedia('(prefers-color-scheme: dark)')`.matches
 */
export function resolveMode(mode: ThemeMode): ResolvedMode {
  if (mode === 'dark') return 'dark'
  if (mode === 'light') return 'light'
  // mode === 'system'
  if (
    typeof window !== 'undefined' &&
    window.matchMedia('(prefers-color-scheme: dark)').matches
  ) {
    return 'dark'
  }
  return 'light'
}

/**
 * Validates and sanitizes any unknown input to a valid ThemePreferences object.
 * Invalid or missing fields fall back to defaults.
 */
export function validatePreferences(raw: unknown): ThemePreferences {
  if (raw === null || raw === undefined || typeof raw !== 'object') {
    return { ...DEFAULT_PREFERENCES }
  }

  const obj = raw as Record<string, unknown>

  const themeMode = VALID_THEME_MODES.includes(obj.themeMode as ThemeMode)
    ? (obj.themeMode as ThemeMode)
    : DEFAULT_PREFERENCES.themeMode

  const colorScheme = VALID_COLOR_SCHEMES.includes(
    obj.colorScheme as ColorScheme,
  )
    ? (obj.colorScheme as ColorScheme)
    : DEFAULT_PREFERENCES.colorScheme

  const fontSize = VALID_FONT_SIZES.includes(obj.fontSize as FontSize)
    ? (obj.fontSize as FontSize)
    : DEFAULT_PREFERENCES.fontSize

  return { themeMode, colorScheme, fontSize }
}

/**
 * Merges local preferences with backend response.
 * For each field: if backend value is non-null and valid, use backend; otherwise use local.
 */
export function mergePreferences(
  local: ThemePreferences,
  backend: DisplayPreferencesResponse,
): ThemePreferences {
  const themeMode =
    backend.themeMode !== null &&
    VALID_THEME_MODES.includes(backend.themeMode as ThemeMode)
      ? (backend.themeMode as ThemeMode)
      : local.themeMode

  const colorScheme =
    backend.colorScheme !== null &&
    VALID_COLOR_SCHEMES.includes(backend.colorScheme as ColorScheme)
      ? (backend.colorScheme as ColorScheme)
      : local.colorScheme

  const fontSize =
    backend.fontSize !== null &&
    VALID_FONT_SIZES.includes(backend.fontSize as FontSize)
      ? (backend.fontSize as FontSize)
      : local.fontSize

  return { themeMode, colorScheme, fontSize }
}
