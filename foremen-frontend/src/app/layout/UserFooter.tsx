import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { LogOut } from 'lucide-react'

import { cn } from '@/lib/utils'
import { useAuthStore } from '@/stores/auth-store'

/**
 * UserFooter — Sidebar footer displaying the current user's avatar (initials),
 * name, and role, read from the Auth_Store. Clicking it opens a small dropdown
 * menu with a "Log out" action.
 *
 * Requirements: 2.4
 */

interface UserFooterProps {
  collapsed?: boolean
}

function getInitials(name: string): string {
  const initials = name
    .trim()
    .split(/\s+/)
    .map((part) => part[0])
    .filter(Boolean)
    .slice(0, 2)
    .join('')
    .toUpperCase()
  return initials || '?'
}

export function UserFooter({ collapsed = false }: Readonly<UserFooterProps>) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const logout = useAuthStore((s) => s.logout)

  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)

  const name = user?.name ?? t('common.loading')
  const role = user?.roleCode ?? ''
  const initials = getInitials(name)

  // Close the menu on outside click or Escape.
  useEffect(() => {
    if (!open) return

    function onPointerDown(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') setOpen(false)
    }

    document.addEventListener('mousedown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  async function handleLogout() {
    setOpen(false)
    await logout()
    navigate('/login')
  }

  return (
    <div ref={containerRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={name}
        className={cn(
          'flex w-full items-center gap-3 rounded-md px-3 py-3 text-left transition-colors hover:bg-accent',
          collapsed && 'justify-center'
        )}
      >
        {/* Avatar circle with initials */}
        <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-secondary text-xs font-medium text-secondary-foreground">
          {initials}
        </div>

        {/* Name and role — hidden when sidebar is collapsed */}
        {!collapsed && (
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-foreground">{name}</p>
            {role && <p className="truncate text-xs text-muted-foreground">{role}</p>}
          </div>
        )}
      </button>

      {open && (
        <div
          role="menu"
          className={cn(
            'absolute bottom-full z-50 mb-2 min-w-40 overflow-hidden rounded-md border border-border bg-popover shadow-md',
            collapsed ? 'left-0' : 'left-3 right-3'
          )}
        >
          <button
            type="button"
            role="menuitem"
            onClick={handleLogout}
            className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-foreground hover:bg-accent"
          >
            <LogOut className="h-4 w-4 shrink-0" aria-hidden="true" />
            <span className="truncate">{t('auth.logout')}</span>
          </button>
        </div>
      )}
    </div>
  )
}
