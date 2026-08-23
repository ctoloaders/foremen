/**
 * MatrixSkeleton — Loading placeholder for the permission matrix grid.
 * Renders 5 placeholder rows and 7 columns (1 wider role-name column + 6 resource columns).
 * Each resource cell contains small placeholders for operation letter buttons.
 *
 * Requirements: 13.2, 13.4
 */

const SKELETON_ROWS = 5
const SKELETON_RESOURCE_COLS = 6

export function MatrixSkeleton() {
  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <div className="min-w-[700px]">
        {/* Header row */}
        <div className="flex items-center border-b border-border px-3 py-2">
          {/* Role name column header — wider */}
          <div className="w-40 shrink-0">
            <div className="h-4 w-20 animate-pulse rounded bg-muted" />
          </div>
          {/* Resource column headers */}
          {Array.from({ length: SKELETON_RESOURCE_COLS }, (_, i) => (
            <div key={i} className="flex-1 px-2">
              <div className="h-4 w-16 animate-pulse rounded bg-muted" />
            </div>
          ))}
        </div>

        {/* Data rows */}
        {Array.from({ length: SKELETON_ROWS }, (_, rowIdx) => (
          <div
            key={rowIdx}
            className="flex items-center border-b border-border px-3 py-2 last:border-b-0"
          >
            {/* Role name cell — wider */}
            <div className="w-40 shrink-0">
              <div className="h-4 w-28 animate-pulse rounded bg-muted" />
            </div>
            {/* Resource / operation cells */}
            {Array.from({ length: SKELETON_RESOURCE_COLS }, (_, colIdx) => (
              <div key={colIdx} className="flex flex-1 items-center gap-1 px-2">
                {/* 4 small squares representing C R U D operation letters */}
                <div className="h-6 w-6 animate-pulse rounded bg-muted" />
                <div className="h-6 w-6 animate-pulse rounded bg-muted" />
                <div className="h-6 w-6 animate-pulse rounded bg-muted" />
                <div className="h-6 w-6 animate-pulse rounded bg-muted" />
              </div>
            ))}
          </div>
        ))}
      </div>
    </div>
  )
}
