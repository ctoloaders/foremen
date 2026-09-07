import { useEffect, useMemo, useRef } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'

import { ApiError, apiRequest } from '@/lib/api-client'
import type { PaginatedResponse } from './types'

/** Page size for each options page fetched by the infinite query. */
export const PAGE_SIZE = 20

/**
 * A single option row returned by a target resource's list endpoint. The
 * backend i18n mapping already resolves the localized display name into the
 * `name` field, so consumers render `name` directly (no client-side locale
 * resolution needed). Only `id` and `name` are consumed.
 */
export interface ReferenceOption {
  id: number
  name: string
}

/**
 * True when an error thrown by {@link apiRequest} is an {@link ApiError}
 * carrying HTTP `403 Forbidden` — the caller lacks the target resource's READ
 * grant. Used to branch the options failure into a degraded "no access" state
 * rather than the retryable network/5xx error state.
 */
export function isForbiddenError(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403
}

/**
 * Build the options request URL for a given page and search term.
 *
 * Shape (per design.md "Operator-symbol correction" — TILDE-WRAPPED grammar):
 *   `${optionsPath}?page=${page}&size=${PAGE_SIZE}&sort=<sort>&query=name~ct~<search>`
 *
 * The `query` param is only included when `search` is non-empty. The tilde
 * operator is kept literal on the wire (`~` is an RFC 3986 unreserved
 * character): we percent-encode the search term but restore any `~` that
 * `encodeURIComponent` produced, matching the existing `fetchRolesPage`
 * convention.
 *
 * `sort` defaults to `'name,asc'` to preserve the existing reference-filter
 * behavior; callers that need a different order (e.g. work categories sorted by
 * `orderNo,asc`) pass it explicitly.
 */
export function buildOptionsUrl(
  optionsPath: string,
  page: number,
  search: string,
  sort: string = 'name,asc',
): string {
  const params = new URLSearchParams()
  params.set('page', String(page))
  params.set('size', String(PAGE_SIZE))
  params.set('sort', sort)

  let url = `${optionsPath}?${params}`
  const term = search.trim()
  if (term) {
    const query = `name~ct~${term}`
    url += `&query=${encodeURIComponent(query).replace(/%7E/gi, '~')}`
  }
  return url
}

export interface UseReferenceOptionsParams {
  /** Options list endpoint (e.g. `/api/roles`). */
  optionsPath: string
  /** Query-key namespace segment (e.g. the target resource code). */
  cacheKey: string
  /** Debounced search term. */
  debouncedSearch: string
  /** Sort field/direction (e.g. `name,asc`, `orderNo,asc`). */
  sort?: string
  /** Disable the query (e.g. when the owning popover is closed). */
  enabled?: boolean
}

/**
 * Shared infinite-scroll + backend-searched options loading, extracted from
 * {@link import('./ReferenceFilter').ReferenceFilter} so the data-table filter
 * and the form-control {@link import('@/components/ui/async-entity-select').AsyncEntitySelect}
 * do not duplicate the query, pagination, and forbidden-handling logic.
 *
 * Data loading: `useInfiniteQuery` keyed by `[cacheKey, debouncedSearch, sort]`,
 * one page at a time via `apiRequest`; debounced search resets to page 0; a
 * `403` is not retried (a missing READ grant never succeeds on retry) while
 * genuine network/5xx failures get the default retry.
 */
export function useReferenceOptions({
  optionsPath,
  cacheKey,
  debouncedSearch,
  sort = 'name,asc',
  enabled = true,
}: UseReferenceOptionsParams) {
  const query = useInfiniteQuery({
    queryKey: ['reference-options', cacheKey, debouncedSearch.trim(), sort],
    queryFn: ({ pageParam }) =>
      apiRequest<PaginatedResponse<ReferenceOption>>(
        buildOptionsUrl(optionsPath, pageParam, debouncedSearch, sort),
      ),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => (lastPage.last ? undefined : lastPage.number + 1),
    staleTime: 60_000,
    enabled,
    retry: (failureCount, err) => !isForbiddenError(err) && failureCount < 3,
  })

  const options = useMemo<ReferenceOption[]>(
    () => query.data?.pages.flatMap((page) => page.content) ?? [],
    [query.data],
  )

  // A 403 on the options query means no READ grant on the target resource.
  const isForbidden = query.isError && isForbiddenError(query.error)

  return { ...query, options, isForbidden }
}

/**
 * Attach an {@link IntersectionObserver} to a sentinel element that fetches the
 * next page when it scrolls into view. No-op when there is no next page or when
 * `IntersectionObserver` is unavailable (e.g. jsdom). Shared verbatim from the
 * original {@link import('./ReferenceFilter').ReferenceFilter} implementation.
 */
export function useInfiniteScrollSentinel(params: {
  hasNextPage: boolean
  isFetchingNextPage: boolean
  fetchNextPage: () => void
  /** Re-arm the observer when the loaded option count changes. */
  optionCount: number
}) {
  const { hasNextPage, isFetchingNextPage, fetchNextPage, optionCount } = params
  const sentinelRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    const sentinel = sentinelRef.current
    if (!sentinel) return
    if (!hasNextPage) return
    if (typeof IntersectionObserver === 'undefined') return

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting && hasNextPage && !isFetchingNextPage) {
          fetchNextPage()
        }
      },
      { threshold: 0.1 },
    )
    observer.observe(sentinel)

    return () => observer.disconnect()
  }, [hasNextPage, isFetchingNextPage, fetchNextPage, optionCount])

  return sentinelRef
}
