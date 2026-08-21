import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { icons, type LucideIcon } from 'lucide-react'
import { cn } from '@/lib/utils'

export interface NavItemProps {
  icon: string
  labelKey: string
  path: string
  active: boolean
  collapsed?: boolean
  variant?: 'sidebar' | 'bottom-nav' | 'drawer'
}

/**
 * Converts a kebab-case icon name to PascalCase for Lucide icon lookup.
 * e.g., 'layout-dashboard' -> 'LayoutDashboard'
 */
function toPascalCase(str: string): string {
  return str
    .split('-')
    .map((segment) => segment.charAt(0).toUpperCase() + segment.slice(1))
    .join('')
}

/**
 * Resolves a Lucide icon component by its kebab-case name.
 * Falls back to Circle icon if the name is not found.
 */
function resolveIcon(name: string): LucideIcon {
  const pascalName = toPascalCase(name)
  return (icons[pascalName as keyof typeof icons] as LucideIcon | undefined) ?? icons.Circle
}

export function NavItem({
  icon,
  labelKey,
  path,
  active,
  collapsed = false,
  variant = 'sidebar',
}: NavItemProps) {
  const { t } = useTranslation()
  const Icon = resolveIcon(icon)
  const label = t(labelKey)

  if (variant === 'bottom-nav') {
    return (
      <Link
        to={path}
        className={cn(
          'flex flex-col items-center justify-center gap-0.5 min-w-[44px] min-h-[44px] text-xs',
          active ? 'text-foreground' : 'text-muted-foreground'
        )}
      >
        <Icon className="h-5 w-5" />
        <span className="truncate max-w-[56px]">{label}</span>
      </Link>
    )
  }

  // sidebar and drawer variants share the same layout
  return (
    <Link
      to={path}
      className={cn(
        'flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors',
        active
          ? 'bg-secondary text-foreground'
          : 'text-muted-foreground hover:bg-secondary/50 hover:text-foreground',
        collapsed && 'justify-center px-2'
      )}
    >
      <Icon className="h-5 w-5 shrink-0" />
      {!collapsed && <span className="truncate">{label}</span>}
    </Link>
  )
}
