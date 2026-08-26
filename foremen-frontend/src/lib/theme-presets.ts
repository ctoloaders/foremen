import type { ColorScheme, FontSize } from '@/features/settings/types'

export interface ColorPreset {
  name: ColorScheme
  label: string // i18n key
  swatchColor: string // preview color for the selector
  dark: {
    primary: string
    primaryForeground: string
    accent: string
    accentForeground: string
    ring: string
  }
  light: {
    primary: string
    primaryForeground: string
    accent: string
    accentForeground: string
    ring: string
  }
}

export const COLOR_PRESETS: ColorPreset[] = [
  {
    name: 'zinc',
    label: 'settings.appearance.colorScheme.zinc',
    swatchColor: '#a1a1aa',
    dark: {
      primary: '#fafafa',
      primaryForeground: '#18181b',
      accent: '#27272a',
      accentForeground: '#fafafa',
      ring: '#d4d4d8',
    },
    light: {
      primary: '#18181b',
      primaryForeground: '#fafafa',
      accent: '#f4f4f5',
      accentForeground: '#18181b',
      ring: '#a1a1aa',
    },
  },
  {
    name: 'slate',
    label: 'settings.appearance.colorScheme.slate',
    swatchColor: '#94a3b8',
    dark: {
      primary: '#f8fafc',
      primaryForeground: '#0f172a',
      accent: '#1e293b',
      accentForeground: '#f8fafc',
      ring: '#cbd5e1',
    },
    light: {
      primary: '#0f172a',
      primaryForeground: '#f8fafc',
      accent: '#f1f5f9',
      accentForeground: '#0f172a',
      ring: '#94a3b8',
    },
  },
  {
    name: 'stone',
    label: 'settings.appearance.colorScheme.stone',
    swatchColor: '#a8a29e',
    dark: {
      primary: '#fafaf9',
      primaryForeground: '#1c1917',
      accent: '#292524',
      accentForeground: '#fafaf9',
      ring: '#d6d3d1',
    },
    light: {
      primary: '#1c1917',
      primaryForeground: '#fafaf9',
      accent: '#f5f5f4',
      accentForeground: '#1c1917',
      ring: '#a8a29e',
    },
  },
  {
    name: 'gray',
    label: 'settings.appearance.colorScheme.gray',
    swatchColor: '#9ca3af',
    dark: {
      primary: '#f9fafb',
      primaryForeground: '#111827',
      accent: '#1f2937',
      accentForeground: '#f9fafb',
      ring: '#d1d5db',
    },
    light: {
      primary: '#111827',
      primaryForeground: '#f9fafb',
      accent: '#f3f4f6',
      accentForeground: '#111827',
      ring: '#9ca3af',
    },
  },
  {
    name: 'neutral',
    label: 'settings.appearance.colorScheme.neutral',
    swatchColor: '#a3a3a3',
    dark: {
      primary: '#fafafa',
      primaryForeground: '#171717',
      accent: '#262626',
      accentForeground: '#fafafa',
      ring: '#d4d4d4',
    },
    light: {
      primary: '#171717',
      primaryForeground: '#fafafa',
      accent: '#f5f5f5',
      accentForeground: '#171717',
      ring: '#a3a3a3',
    },
  },
  {
    name: 'blue',
    label: 'settings.appearance.colorScheme.blue',
    swatchColor: '#3b82f6',
    dark: {
      primary: '#3b82f6',
      primaryForeground: '#ffffff',
      accent: '#1e3a5f',
      accentForeground: '#e0f2fe',
      ring: '#60a5fa',
    },
    light: {
      primary: '#2563eb',
      primaryForeground: '#ffffff',
      accent: '#eff6ff',
      accentForeground: '#1e40af',
      ring: '#3b82f6',
    },
  },
  {
    name: 'green',
    label: 'settings.appearance.colorScheme.green',
    swatchColor: '#22c55e',
    dark: {
      primary: '#22c55e',
      primaryForeground: '#ffffff',
      accent: '#14532d',
      accentForeground: '#dcfce7',
      ring: '#4ade80',
    },
    light: {
      primary: '#16a34a',
      primaryForeground: '#ffffff',
      accent: '#f0fdf4',
      accentForeground: '#166534',
      ring: '#22c55e',
    },
  },
  {
    name: 'orange',
    label: 'settings.appearance.colorScheme.orange',
    swatchColor: '#f97316',
    dark: {
      primary: '#f97316',
      primaryForeground: '#ffffff',
      accent: '#431407',
      accentForeground: '#fed7aa',
      ring: '#fb923c',
    },
    light: {
      primary: '#ea580c',
      primaryForeground: '#ffffff',
      accent: '#fff7ed',
      accentForeground: '#9a3412',
      ring: '#f97316',
    },
  },
  {
    name: 'red',
    label: 'settings.appearance.colorScheme.red',
    swatchColor: '#ef4444',
    dark: {
      primary: '#ef4444',
      primaryForeground: '#ffffff',
      accent: '#450a0a',
      accentForeground: '#fecaca',
      ring: '#f87171',
    },
    light: {
      primary: '#dc2626',
      primaryForeground: '#ffffff',
      accent: '#fef2f2',
      accentForeground: '#991b1b',
      ring: '#ef4444',
    },
  },
]

export const FONT_SIZE_MAP: Record<FontSize, string> = {
  sm: '12px',
  default: '14px',
  lg: '16px',
  xl: '18px',
}
