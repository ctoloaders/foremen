/**
 * AsyncEntitySelect — a controlled, single-select async combobox for entity
 * (foreign-key) form fields (FOR-04-bugs Bug 7 / Req 2.7).
 *
 * It replaces the native `<select>` populated by a one-shot `useReferenceOptions(size=200)`
 * with a searchable, server-paged combobox that reuses the exact data-loading logic of the
 * data-table {@link import('@/components/data-table/ReferenceFilter').ReferenceFilter}:
 * the shared {@link useReferenceOptions} infinite query (keyed by resource + debounced
 * search, `sort` configurable) plus the shared IntersectionObserver sentinel for next-page
 * loading. Loading / empty / error / no-access states reuse the existing `referenceFilter.*`
 * i18n keys.
 *
 * Control contract (react-hook-form friendly):
 *   - `value`: the selected entity id (`number`), or `null` when nothing is selected. The
 *     forms treat `0` as "unselected"; callers map `0 ↔ null` at the boundary.
 *   - `onChange(id | null)`: emits the newly selected id (picking an option) or `null`
 *     (clearing). This mirrors an `<input>`/`<select>` onChange closely enough to drive
 *     RHF via `setValue`/`Controller`.
 *
 * Selected-label resolution: the trigger shows the selected option's `name`. Because the
 * selected id may not be on a currently-loaded page (e.g. edit-prefill of a deep record),
 * the component keeps a "last-known label" cache: whenever the selected id appears on a
 * loaded page its name is remembered, and an optional `selectedLabel` prop provides an
 * initial label so edit mode shows a real name immediately. Until a label is known the
 * trigger falls back to `#<id>`.
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertTriangle, Check, ChevronsUpDown, Loader2, Lock, RotateCw } from 'lucide-react'

import { cn } from '@/lib/utils'
import { useDebounce } from '@/hooks/useDebounce'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import {
  useInfiniteScrollSentinel,
  useReferenceOptions,
} from '@/components/data-table/use-reference-options'

export interface AsyncEntitySelectProps {
  /** Selected entity id, or `null` when nothing is selected. */
  value: number | null
  /** Emits the picked entity id, or `null` when the selection is cleared. */
  onChange: (id: number | null) => void
  /** Options list endpoint (e.g. `/api/projects`). */
  optionsPath: string
  /** Sort field/direction for the options query (default `name,asc`). */
  sort?: string
  /** Trigger placeholder shown when no option is selected. */
  placeholder?: string
  /**
   * Initial label for the currently-selected id, used so edit-prefill shows a
   * real name before the option's page loads. Optional.
   */
  selectedLabel?: string | null
  disabled?: boolean
  id?: string
  'aria-label'?: string
}

export function AsyncEntitySelect({
  value,
  onChange,
  optionsPath,
  sort = 'name,asc',
  placeholder,
  selectedLabel,
  disabled,
  id,
  'aria-label': ariaLabel,
}: Readonly<AsyncEntitySelectProps>) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const debouncedSearch = useDebounce(search, 300)

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
    cacheKey: optionsPath,
    debouncedSearch,
    sort,
    // Only fetch while the popover is open (mirrors the form's prior
    // `enabled: open` one-shot fetch, avoiding requests for closed sheets).
    enabled: open,
  })

  // Last-known label cache: remember the name of any id we've seen on a loaded
  // page so the trigger can render the selected label even after search/paging
  // scrolls it out of `options`. Seeded from `selectedLabel` for edit-prefill.
  const labelCacheRef = useRef<Map<number, string>>(new Map())
  useEffect(() => {
    if (value != null && selectedLabel) {
      labelCacheRef.current.set(value, selectedLabel)
    }
  }, [value, selectedLabel])
  for (const option of options) labelCacheRef.current.set(option.id, option.name)

  const selectedText = useMemo(() => {
    if (value == null) return null
    return labelCacheRef.current.get(value) ?? `#${value}`
  }, [value, options])

  // --- Infinite scroll sentinel (shared IntersectionObserver logic) ---
  const sentinelRef = useInfiniteScrollSentinel({
    hasNextPage,
    isFetchingNextPage,
    fetchNextPage,
    optionCount: options.length,
  })

  const handlePick = (pickedId: number) => {
    onChange(pickedId)
    setOpen(false)
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          id={id}
          type="button"
          variant="outline"
          role="combobox"
          aria-expanded={open}
          aria-label={ariaLabel}
          disabled={disabled}
          className={cn(
            'h-10 w-full justify-between font-normal',
            value == null && 'text-muted-foreground',
          )}
        >
          <span className="truncate">
            {selectedText ?? (
              <span className="text-muted-foreground">{placeholder ?? <>&mdash;</>}</span>
            )}
          </span>
          <ChevronsUpDown className="ml-2 h-4 w-4 shrink-0 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-[--radix-popover-trigger-width] p-0" align="start">
        {isForbidden ? (
          // No READ grant on the target resource: inert, disabled hint.
          <div
            className="flex items-center gap-2 px-3 py-4 text-sm text-muted-foreground"
            aria-disabled="true"
            data-forbidden="true"
          >
            <Lock className="h-4 w-4 shrink-0" />
            <span>{t('referenceFilter.noAccess')}</span>
          </div>
        ) : (
          <div className="flex flex-col">
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
                <>
                  {options.map((option) => {
                    const isSelected = option.id === value
                    return (
                      <button
                        key={option.id}
                        type="button"
                        role="option"
                        aria-selected={isSelected}
                        onClick={() => handlePick(option.id)}
                        className={cn(
                          'flex w-full select-none items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm hover:bg-accent hover:text-accent-foreground',
                          isSelected && 'bg-accent/50',
                        )}
                      >
                        <Check
                          className={cn(
                            'h-4 w-4 shrink-0',
                            isSelected ? 'opacity-100' : 'opacity-0',
                          )}
                        />
                        <span className="flex-1 truncate">{option.name}</span>
                      </button>
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
                </>
              )}
            </div>
          </div>
        )}
      </PopoverContent>
    </Popover>
  )
}
