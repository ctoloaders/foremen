/**
 * SkeletonPage — Suspense fallback for lazy-loaded route pages.
 * Renders shimmer-animated placeholder content simulating page structure.
 *
 * Requirements: 7.1, 7.2, 7.3, 7.5
 */
export function SkeletonPage() {
  return (
    <div className="w-full space-y-6">
      {/* Title placeholder — 40–60% width */}
      <div className="h-7 w-1/2 animate-pulse rounded bg-muted" />

      {/* Subtitle placeholder — 60–80% width */}
      <div className="h-4 w-2/3 animate-pulse rounded bg-muted" />

      {/* Placeholder cards grid — responsive: 1 col mobile, 2 col md, 3 col lg */}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
        {Array.from({ length: 4 }, (_, i) => (
          <div
            key={i}
            className="space-y-3 rounded-lg border border-border bg-card p-4"
          >
            {/* Card inner blocks simulating content structure */}
            <div className="h-5 w-3/4 animate-pulse rounded bg-muted" />
            <div className="h-4 w-full animate-pulse rounded bg-muted" />
            <div className="h-4 w-5/6 animate-pulse rounded bg-muted" />
          </div>
        ))}
      </div>
    </div>
  );
}
