/**
 * RolesListSkeleton — Loading placeholder for the roles list.
 * Desktop: 5 skeleton table rows with shimmer cells.
 * Mobile (hidden on md+): 5 skeleton cards.
 *
 * Requirements: 1.5, 13.1, 13.2
 */
export function RolesListSkeleton() {
  return (
    <>
      {/* Desktop table skeleton — hidden below md */}
      <div className="hidden md:block">
        <div className="rounded-lg border border-border">
          {/* Table header */}
          <div className="flex items-center gap-4 border-b border-border px-4 py-3">
            <div className="h-4 w-20 animate-pulse rounded bg-muted" />
            <div className="h-4 w-28 animate-pulse rounded bg-muted" />
            <div className="h-4 w-40 animate-pulse rounded bg-muted" />
            <div className="h-4 w-16 animate-pulse rounded bg-muted" />
            <div className="ml-auto h-4 w-20 animate-pulse rounded bg-muted" />
          </div>

          {/* 5 skeleton rows */}
          {Array.from({ length: 5 }, (_, i) => (
            <div
              key={i}
              className="flex items-center gap-4 border-b border-border px-4 py-3 last:border-b-0"
            >
              <div className="h-4 w-24 animate-pulse rounded bg-muted" />
              <div className="h-4 w-32 animate-pulse rounded bg-muted" />
              <div className="h-4 w-48 animate-pulse rounded bg-muted" />
              <div className="h-5 w-14 animate-pulse rounded-full bg-muted" />
              <div className="ml-auto flex gap-2">
                <div className="h-8 w-8 animate-pulse rounded bg-muted" />
                <div className="h-8 w-8 animate-pulse rounded bg-muted" />
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Mobile cards skeleton — visible below md */}
      <div className="space-y-3 md:hidden">
        {Array.from({ length: 5 }, (_, i) => (
          <div
            key={i}
            className="space-y-3 rounded-lg border border-border p-4"
          >
            <div className="flex items-center justify-between">
              <div className="h-5 w-28 animate-pulse rounded bg-muted" />
              <div className="h-5 w-14 animate-pulse rounded-full bg-muted" />
            </div>
            <div className="h-4 w-40 animate-pulse rounded bg-muted" />
            <div className="h-4 w-56 animate-pulse rounded bg-muted" />
            <div className="flex gap-2 pt-1">
              <div className="h-8 w-16 animate-pulse rounded bg-muted" />
              <div className="h-8 w-16 animate-pulse rounded bg-muted" />
            </div>
          </div>
        ))}
      </div>
    </>
  )
}
