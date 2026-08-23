/**
 * UserFormSkeleton — Placeholder skeleton for the user form while loading.
 * Displays shimmer placeholders matching the form layout.
 */
export function UserFormSkeleton() {
  return (
    <div className="space-y-5">
      {/* Name / Email row */}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <div className="space-y-2">
          <div className="h-4 w-16 animate-pulse rounded bg-muted" />
          <div className="h-9 w-full animate-pulse rounded-md bg-muted" />
        </div>
        <div className="space-y-2">
          <div className="h-4 w-16 animate-pulse rounded bg-muted" />
          <div className="h-9 w-full animate-pulse rounded-md bg-muted" />
        </div>
      </div>

      {/* Phone / Locale row */}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <div className="space-y-2">
          <div className="h-4 w-20 animate-pulse rounded bg-muted" />
          <div className="h-9 w-full animate-pulse rounded-md bg-muted" />
        </div>
        <div className="space-y-2">
          <div className="h-4 w-14 animate-pulse rounded bg-muted" />
          <div className="h-9 w-full animate-pulse rounded-md bg-muted" />
        </div>
      </div>

      {/* Role */}
      <div className="space-y-2">
        <div className="h-4 w-12 animate-pulse rounded bg-muted" />
        <div className="h-9 w-full animate-pulse rounded-md bg-muted" />
      </div>

      {/* Active checkbox */}
      <div className="flex items-center gap-2">
        <div className="h-4 w-4 animate-pulse rounded bg-muted" />
        <div className="h-4 w-20 animate-pulse rounded bg-muted" />
      </div>
    </div>
  )
}
