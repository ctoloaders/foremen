import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useInfiniteQuery } from '@tanstack/react-query'
import { AlertTriangle, Check, Loader2, Lock, RotateCw, X } from 'lucide-react'

import { cn } from '@/lib/utils'
import { useDebounce } from '@/hooks/useDebounce'
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip'
import { ApiError, apiRequest } from '@/lib/api-client'
import type { PaginatedResponse, ReferenceInfo } from './types'

/** Page size for each options page fetched by the infinite query. */
const PAGE_SIZE = 20

/**
 * A single option row returned by the target resource's list endpoint. The
 * backend i18n mapping already resolves the localized display name into the
 * `name` field, so the component renders `name` directly (no client-side
 * locale resolution needed). Only `id` and `name` are consumed here.
 */
export interface ReferenceOption {
  id: number
  name: string
}

export interface ReferenceFilterProps {
  /** Reference descriptor from the column metadata. */
  reference: ReferenceInfo
  /** Selected target-entity ids (controlled). */
  value?: number[]
  /**
   * Emit the new selection. An empty array means the filter is cleared (the
   * DataTable drops it). Selection has no explicit single/multi mode: clicking
   * an option's name replaces the whole selection with that one id, while
   * clicking its checkbox toggles membership (add / remove).
   */
  onChange?: (ids: number[]) => void
  /**
   * Close the dropdown. Invoked when picking a single option by its name
   * (replace + close). Optional so the component works without an owning
   * popover. Toggling checkboxes does NOT close the dropdown.
   */
  onClose?: () => void
  /**
   * Notifies the owner (the DataTable, task 7.1) whether the options query
   * failed with a `403 Forbidden` — i.e. the caller has no READ grant on the
   * target resource (Req 5.5). The DataTable can use this to render the whole
   * control in a degraded/disabled affordance while still loading the table
   * itself. The component also degrades internally (disabled control + a
   * localized "no access" hint), so wiring this is optional.
   */
  onForbiddenChange?: (isForbidden: boolean) => void
}

/**
 * True when an error thrown by {@link apiRequest} is an {@link ApiError}
 * carrying HTTP `403 Forbidden` — the caller lacks the target resource's READ
 * grant. Used to branch the options failure into the degraded "no access"
 * state (Req 5.5) rather than the retryable network/5xx error state (Req 6.2).
 */
function isForbiddenError(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403
}

/**
 * Build the options request URL for a given page and search term.
 *
 * Shape (per design.md "Operator-symbol correction" — TILDE-WRAPPED grammar):
 *   `${optionsPath}?page=${page}&size=${PAGE_SIZE}&sort=name,asc&query=name~ct~<search>`
 *
 * The `query` param is only included when `search` is non-empty. The tilde
 * operator is kept literal on the wire (`~` is an RFC 3986 unreserved
 * character): we percent-encode the search term but restore any `~` that
 * `encodeURIComponent` produced, matching the existing `fetchRolesPage`
 * convention.
 */
export function buildOptionsUrl(
  optionsPath: string,
  page: number,
  search: string,
): string {
  const params = new URLSearchParams()
  params.set('page', String(page))
  params.set('size', String(PAGE_SIZE))
  params.set('sort', 'name,asc')

  let url = `${optionsPath}?${params}`
  const term = search.trim()
  if (term) {
    const query = `name~ct~${term}`
    url += `&query=${encodeURIComponent(query).replace(/%7E/gi, '~')}`
  }
  return url
}

/**
 * Infinite-scroll, backend-searched reference (association) dropdown.
 *
 *  - Data loading: `useInfiniteQuery` keyed by `[targetResource,
 *    debouncedSearch]`, one page at a time via `apiRequest`; next page on
 *    scroll-end; debounced search resets to page 0; options rendered by their
 *    locale-resolved `name`; localized loading/empty states.
 *  - Selection (no explicit mode toggle): a square checkbox is always shown on
 *    every row. Clicking the option's NAME picks exactly that one (replaces the
 *    whole selection and closes the dropdown) — the "select one" gesture.
 *    Clicking the CHECKBOX toggles that id's membership — the "select several"
 *    gesture: adding a new id (which may be the first), or removing one; when
 *    the last selected checkbox is removed the selection becomes empty and the
 *    DataTable drops the filter. Selected values display the option's
 *    locale-resolved `name`, falling back to `#<id>` for ids not on the loaded
 *    pages.
 *
 * Controlled: `value`/`onChange` come from props (the DataTable owns state).
 * `value` defaults to an empty selection so the component is usable standalone.
 */
export function ReferenceFilter({
  reference,
  value = [],
  onChange,
  onClose,
  onForbiddenChange,
}: ReferenceFilterProps) {
  const { t } = useTranslation()
  const [search, setSearch] = useState('')
  const debouncedSearch = useDebounce(search, 300)

  const { optionsPath, targetResource } = reference

  const {
    data,
    isLoading,
    isError,
    error,
    refetch,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useInfiniteQuery({
    queryKey: ['reference-options', targetResource, debouncedSearch.trim()],
    queryFn: ({ pageParam }) =>
      apiRequest<PaginatedResponse<ReferenceOption>>(
        buildOptionsUrl(optionsPath, pageParam, debouncedSearch),
      ),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => (lastPage.last ? undefined : lastPage.number + 1),
    staleTime: 60_000,
    // A missing READ grant (403) will never succeed on retry, so do not retry
    // it; genuine network/5xx failures get React Query's default retry and can
    // also be retried manually via the in-dropdown retry action.
    retry: (failureCount, err) => !isForbiddenError(err) && failureCount < 3,
  })

  // A 403 on the options query means no READ grant on the target resource:
  // render a disabled control with a localized "no access" hint (the table
  // itself still loads — this is a degraded control, not a thrown error). Any
  // other failure (network / 5xx) is a retryable error shown inside the
  // dropdown, and MUST NOT surface the "no access" state.
  const isForbidden = isError && isForbiddenError(error)

  // Surface the forbidden state to the owning DataTable so it can degrade the
  // whole control if it wishes (Req 5.5). Fires on transitions only.
  useEffect(() => {
    onForbiddenChange?.(isForbidden)
  }, [isForbidden, onForbiddenChange])

  const options = useMemo<ReferenceOption[]>(
    () => data?.pages.flatMap((page) => page.content) ?? [],
    [data],
  )

  /**
   * Map of loaded option id → localized name, used to render selected-value
   * labels (and to degrade gracefully for selected ids not yet loaded).
   */
  const nameById = useMemo(() => {
    const map = new Map<number, string>()
    for (const option of options) map.set(option.id, option.name)
    return map
  }, [options])

  const selected = useMemo(() => new Set(value), [value])

  // --- Selection handlers ---
  // "Select one": clicking the option's name replaces the entire selection with
  // that single id and closes the dropdown.
  const handlePickOne = (id: number) => {
    onChange?.([id])
    onClose?.()
  }

  // "Select several": clicking the checkbox toggles that id's membership.
  // Removing the last selected id yields an empty selection (filter dropped);
  // adding an id appends it (it may be the first). The dropdown stays open.
  const handleToggle = (id: number) => {
    if (selected.has(id)) {
      onChange?.(value.filter((v) => v !== id))
    } else {
      onChange?.([...value, id])
    }
  }

  const handleClear = () => onChange?.([])

  // --- Infinite scroll: observe a sentinel at the bottom of the list ---
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
  }, [hasNextPage, isFetchingNextPage, fetchNextPage, options.length])

  // --- 403: no READ grant on the target resource (Req 5.5) ---
  // Render a disabled control with a localized "no access" hint. The control
  // is inert (no search, no options, no mode toggle) but the surrounding table
  // keeps loading — this is graceful degradation, not an error.
  if (isForbidden) {
    return (
      <div
        className="flex flex-col"
        aria-disabled="true"
        data-forbidden="true"
      >
        <div className="flex items-center gap-2 px-3 py-4 text-sm text-muted-foreground">
          <Lock className="h-4 w-4 shrink-0" />
          <span>{t('referenceFilter.noAccess')}</span>
        </div>
      </div>
    )
  }

  return (
    <div className="flex flex-col">
      {/* Header: clear-all. There is no mode toggle — the gesture (name click
          vs checkbox click) determines single vs multi selection. */}
      <div className="flex items-center justify-end border-b px-3 py-2">
        <button
          type="button"
          onClick={handleClear}
          disabled={value.length === 0}
          className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground disabled:pointer-events-none disabled:opacity-50"
        >
          <X className="h-3 w-3" />
          {t('referenceFilter.clear')}
        </button>
      </div>

      {/* Selected value summary */}
      {value.length > 0 && (
        <div className="flex flex-wrap gap-1 border-b px-3 py-2">
          {value.map((id) => (
            <span
              key={id}
              className="inline-flex max-w-full items-center gap-1 rounded-full bg-secondary px-2 py-0.5 text-xs text-secondary-foreground"
            >
              <span className="truncate">{nameById.get(id) ?? `#${id}`}</span>
              <button
                type="button"
                onClick={() => onChange?.(value.filter((v) => v !== id))}
                aria-label={t('referenceFilter.clear')}
                className="shrink-0 hover:text-foreground"
              >
                <X className="h-3 w-3" />
              </button>
            </span>
          ))}
        </div>
      )}

      {/* Search input */}
      <div className="border-b px-3 py-2">
        <input
          type="text"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder={t('referenceFilter.searchPlaceholder')}
          className="h-8 w-full bg-transparent text-sm outline-none placeholder:text-muted-foreground"
          autoFocus
        />
      </div>

      {/* Options list */}
      <div className="max-h-60 overflow-y-auto p-1">
        {isLoading ? (
          <div className="flex items-center justify-center px-2 py-4">
            <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
            <span className="sr-only">{t('referenceFilter.loading')}</span>
          </div>
        ) : isError ? (
          /* Network / 5xx failure (403 is handled above as a disabled control):
             show a localized error and a retry action that re-runs the query. */
          <div
            role="alert"
            className="flex flex-col items-center gap-2 px-2 py-4 text-center"
          >
            <AlertTriangle className="h-5 w-5 text-destructive" />
            <span className="text-sm text-muted-foreground">
              {t('referenceFilter.error')}
            </span>
            <button
              type="button"
              onClick={() => refetch()}
              className="inline-flex items-center gap-1 rounded-md border px-2 py-1 text-xs font-medium text-foreground hover:bg-accent hover:text-accent-foreground"
            >
              <RotateCw className="h-3 w-3" />
              {t('referenceFilter.retry')}
            </button>
          </div>
        ) : options.length === 0 ? (
          <div className="px-2 py-4 text-center text-sm text-muted-foreground">
            {t('referenceFilter.empty')}
          </div>
        ) : (
          <TooltipProvider delayDuration={300}>
            {options.map((option) => {
              const isSelected = selected.has(option.id)
              return (
                <div
                  key={option.id}
                  role="option"
                  aria-selected={isSelected}
                  className={cn(
                    'group flex w-full select-none items-center gap-2 rounded-sm px-2 py-1.5 text-sm',
                    isSelected && 'bg-accent/50',
                  )}
                >
                  {/* Checkbox — "select several": always a SQUARE checkbox,
                      toggles this id's membership. */}
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <button
                        type="button"
                        role="checkbox"
                        aria-checked={isSelected}
                        aria-label={option.name}
                        onClick={() => handleToggle(option.id)}
                        className={cn(
                          'flex h-4 w-4 shrink-0 items-center justify-center rounded-[3px] border transition-colors',
                          isSelected
                            ? 'border-primary bg-primary text-primary-foreground'
                            : 'border-input hover:border-primary',
                        )}
                      >
                        {isSelected && <Check className="h-3 w-3" />}
                      </button>
                    </TooltipTrigger>
                    <TooltipContent side="left">
                      {t('referenceFilter.selectMultiple')}
                    </TooltipContent>
                  </Tooltip>

                  {/* Name — "select one": replaces the whole selection with this
                      id and closes the dropdown. Hover highlights it as a
                      clickable single-pick target. */}
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <button
                        type="button"
                        onClick={() => handlePickOne(option.id)}
                        className="flex-1 truncate rounded-sm px-1 py-0.5 text-left hover:bg-accent hover:text-accent-foreground"
                      >
                        {option.name}
                      </button>
                    </TooltipTrigger>
                    <TooltipContent side="right">
                      {t('referenceFilter.selectOne')}
                    </TooltipContent>
                  </Tooltip>
                </div>
              )
            })}

            {/* Infinite-scroll sentinel + next-page loading indicator */}
            <div ref={sentinelRef} />
            {isFetchingNextPage && (
              <div className="flex items-center justify-center py-2">
                <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
                <span className="sr-only">{t('referenceFilter.loading')}</span>
              </div>
            )}
          </TooltipProvider>
        )}
      </div>
    </div>
  )
}
