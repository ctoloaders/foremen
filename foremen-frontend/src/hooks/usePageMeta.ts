import { useLocation } from 'react-router-dom'

import { NAV_CONFIG } from '@/config/navigation'

export interface PageMeta {
  titleKey: string
  action?: {
    labelKey: string
    onClick: () => void
  }
}

export function usePageMeta(): PageMeta {
  const { pathname } = useLocation()

  const matchedItem = NAV_CONFIG.flatMap((section) => section.items).find(
    (item) => item.path === pathname,
  )

  return {
    titleKey: matchedItem?.labelKey ?? 'nav.dashboard',
  }
}
