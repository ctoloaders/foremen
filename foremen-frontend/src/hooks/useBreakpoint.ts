import { useEffect, useState } from 'react'

export type Breakpoint = 'mobile' | 'tablet' | 'desktop'

const MOBILE_QUERY = '(max-width: 767px)'
const TABLET_QUERY = '(min-width: 768px) and (max-width: 1024px)'

function getBreakpoint(mobile: boolean, tablet: boolean): Breakpoint {
  if (mobile) return 'mobile'
  if (tablet) return 'tablet'
  return 'desktop'
}

export function useBreakpoint(): Breakpoint {
  const [breakpoint, setBreakpoint] = useState<Breakpoint>(() => {
    if (typeof window === 'undefined') return 'desktop'
    const mobile = window.matchMedia(MOBILE_QUERY).matches
    const tablet = window.matchMedia(TABLET_QUERY).matches
    return getBreakpoint(mobile, tablet)
  })

  useEffect(() => {
    const mobileMediaQuery = window.matchMedia(MOBILE_QUERY)
    const tabletMediaQuery = window.matchMedia(TABLET_QUERY)

    const update = () => {
      setBreakpoint(getBreakpoint(mobileMediaQuery.matches, tabletMediaQuery.matches))
    }

    mobileMediaQuery.addEventListener('change', update)
    tabletMediaQuery.addEventListener('change', update)

    return () => {
      mobileMediaQuery.removeEventListener('change', update)
      tabletMediaQuery.removeEventListener('change', update)
    }
  }, [])

  return breakpoint
}
