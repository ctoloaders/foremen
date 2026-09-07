import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertTriangle, Check, Loader2, Lock, RotateCw, X } from 'lucide-react'

import { cn } from '@/lib/utils'
import { useDebounce } from '@/hooks/useDebounce'
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip'
import type { ReferenceInfo } from './types'
import {
  useInfiniteScrollSentinel,
  useReferenceOptions,
} from './use-reference-options'

// Re-exported so existing importers (`import { buildOptionsUrl, ReferenceOption }
// from '../ReferenceFilter'`) keep working after the shared logic was extracted
// into `./use-reference-options`. Behavior is unchanged: `buildOptionsUrl`
// defaults to `sort=name,asc`.
export { buildOptionsUrl } from './use-reference-options'
export type { ReferenceOption } from './use-reference-options'

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

  // Shared infinite-scroll + backend-searched options loading (extracted into
  // `use-reference-options` so this filter and the form-control AsyncEntitySelect
  // share one implementation). Behavior here is unchanged: keyed by the target
  // resource + search, `sort=name,asc`, 403 not retried.
  const {
    isLoading,
    isError,
    refetch,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
    options,
    isForbidden,
  } = useReferenceOptions({
    optionsPath,
    cacheKey: targetResource,
    debouncedSearch,
  })

  // A 403 on the options query means no READ grant on the target resource:
  // render a disabled control with a localized "no access" hint (the table
  // itself still loads — this is a degraded control, not a thrown error). Any
  // other failure (network / 5xx) is a retryable error shown inside the
  // dropdown, and MUST NOT surface the "no access" state.

  // Surface the forbidden state to the owning DataTable so it can degrade the
  // whole control if it wishes (Req 5.5). Fires on transitions only.
  useEffect(() => {
    onForbiddenChange?.(isForbidden)
  }, [isForbidden, onForbiddenChange])

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
  // (shared IntersectionObserver logic; behavior unchanged).
  const sentinelRef = useInfiniteScrollSentinel({
    hasNextPage,
    isFetchingNextPage,
    fetchNextPage,
    optionCount: options.length,
  })

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
