import { create } from 'zustand'

interface UIState {
  sidebarOpen: boolean
  locale: 'pl' | 'ru'
  toggleSidebar: () => void
  setLocale: (locale: 'pl' | 'ru') => void
}

export const useUIStore = create<UIState>((set) => ({
  sidebarOpen: true,
  locale: 'pl',
  toggleSidebar: () => set((s) => ({ sidebarOpen: !s.sidebarOpen })),
  setLocale: (locale) => set({ locale }),
}))
