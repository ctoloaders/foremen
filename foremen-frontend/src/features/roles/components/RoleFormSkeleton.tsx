/**
 * RoleFormSkeleton — Loading placeholder for the role form sheet fields.
 * Displays skeleton blocks for: code, nameRU, namePL, descriptionRU, descriptionPL, system checkbox.
 *
 * Requirements: 13.2, 13.3
 */
export function RoleFormSkeleton() {
  return (
    <div className="space-y-5 p-1">
      {/* Code field */}
      <div className="space-y-2">
        <div className="h-4 w-16 animate-pulse rounded bg-muted" />
        <div className="h-10 w-full animate-pulse rounded bg-muted" />
      </div>

      {/* Name RU / Name PL — two columns on desktop */}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <div className="space-y-2">
          <div className="h-4 w-20 animate-pulse rounded bg-muted" />
          <div className="h-10 w-full animate-pulse rounded bg-muted" />
        </div>
        <div className="space-y-2">
          <div className="h-4 w-20 animate-pulse rounded bg-muted" />
          <div className="h-10 w-full animate-pulse rounded bg-muted" />
        </div>
      </div>

      {/* Description RU */}
      <div className="space-y-2">
        <div className="h-4 w-28 animate-pulse rounded bg-muted" />
        <div className="h-20 w-full animate-pulse rounded bg-muted" />
      </div>

      {/* Description PL */}
      <div className="space-y-2">
        <div className="h-4 w-28 animate-pulse rounded bg-muted" />
        <div className="h-20 w-full animate-pulse rounded bg-muted" />
      </div>

      {/* System checkbox */}
      <div className="flex items-center gap-3">
        <div className="h-5 w-5 animate-pulse rounded bg-muted" />
        <div className="h-4 w-24 animate-pulse rounded bg-muted" />
      </div>
    </div>
  )
}
