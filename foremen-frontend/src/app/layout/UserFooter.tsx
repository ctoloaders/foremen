import { cn } from '@/lib/utils'

/**
 * UserFooter — Sidebar footer displaying user avatar (initials), name, and role.
 * Uses placeholder data until authentication is implemented.
 *
 * Requirements: 2.4
 */

interface UserFooterProps {
  collapsed?: boolean
}

const USER_NAME = 'Jan Kowalski'
const USER_ROLE = 'Administrator'

function getInitials(name: string): string {
  return name
    .split(' ')
    .map((part) => part[0])
    .join('')
    .toUpperCase()
}

export function UserFooter({ collapsed = false }: UserFooterProps) {
  const initials = getInitials(USER_NAME)

  return (
    <div className="flex items-center gap-3 px-3 py-3">
      {/* Avatar circle with initials */}
      <div
        className={cn(
          'flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-secondary text-xs font-medium text-secondary-foreground'
        )}
      >
        {initials}
      </div>

      {/* Name and role — hidden when sidebar is collapsed */}
      {!collapsed && (
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium text-foreground">
            {USER_NAME}
          </p>
          <p className="truncate text-xs text-muted-foreground">{USER_ROLE}</p>
        </div>
      )}
    </div>
  )
}
