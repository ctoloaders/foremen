// --- Theme Types ---

/** User-selected theme mode. "system" follows OS preference. */
export type ThemeMode = 'dark' | 'light' | 'system'

/** Resolved theme mode after system preference evaluation. */
export type ResolvedMode = 'dark' | 'light'

/** Available color scheme presets. */
export type ColorScheme =
  | 'zinc'
  | 'slate'
  | 'stone'
  | 'gray'
  | 'neutral'
  | 'blue'
  | 'green'
  | 'orange'
  | 'red'

/** Available font size options. */
export type FontSize = 'sm' | 'default' | 'lg' | 'xl'

/** Combined theme preferences (used in store and localStorage). */
export interface ThemePreferences {
  themeMode: ThemeMode
  colorScheme: ColorScheme
  fontSize: FontSize
}

// --- API Response Types ---

/** Returned by GET /api/users/{id}/display-preferences. Fields may be null if unset. */
export interface DisplayPreferencesResponse {
  themeMode: string | null
  colorScheme: string | null
  fontSize: string | null
}

// --- API Request Types ---

/** PATCH /api/users/{id}/display-preferences request body. */
export interface DisplayPreferencesRequest {
  themeMode: ThemeMode
  colorScheme: ColorScheme
  fontSize: FontSize
}
