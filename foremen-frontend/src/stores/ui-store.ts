import { create } from 'zustand'

const LOCALE_STORAGE_KEY = 'foremen-locale'

function getPersistedLocale(): 'pl' | 'ru' {
  try {
    const stored = localStorage.getItem(LOCALE_STORAGE_KEY)
    if (stored === 'pl' || stored === 'ru') return stored
  } catch {
    // localStorage unavailable
  }
  return 'pl'
}

interface UIState {
  sidebarOpen: boolean
  locale: 'pl' | 'ru'
  toggleSidebar: () => void
  setLocale: (locale: 'pl' | 'ru') => void
}

export const useUIStore = create<UIState>((set) => ({
  sidebarOpen: true,
  locale: getPersistedLocale(),
  toggleSidebar: () => set((s) => ({ sidebarOpen: !s.sidebarOpen })),
  setLocale: (locale) => {
    try {
      localStorage.setItem(LOCALE_STORAGE_KEY, locale)
    } catch {
      // localStorage unavailable
    }
    set({ locale })
  },
}))
