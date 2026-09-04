import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, ChevronsUpDown, Loader2, AlertCircle, RotateCcw } from 'lucide-react'

import { cn } from '@/lib/utils'
import { useDebounce } from '@/hooks/useDebounce'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { EXCLUDED_ROLE_CODES, useRolesInfinite } from '../api/query-hooks'
import type { RoleOption } from '../types'

const EXCLUDED_CODES = new Set<string>(EXCLUDED_ROLE_CODES)

interface RoleSelectProps {
  value: number | undefined
  onChange: (value: number) => void
  error?: string
  disabled?: boolean
  /**
   * Display name of the user's current role, used only for edit-form context
   * (Requirement 11.4). When the current role is one of the excluded codes
   * (ADMIN/CLIENT) it is not present in the fetched options, so the trigger
   * falls back to this name to show the current role for context. It is never
   * added to the selectable options list.
   */
  currentRoleName?: string
}

export function RoleSelect({
  value,
  onChange,
  error,
  disabled,
  currentRoleName,
}: RoleSelectProps) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const debouncedSearch = useDebounce(search, 300)

  const {
    data,
    isLoading,
    isError,
    refetch,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useRolesInfinite(debouncedSearch.trim() || undefined)

  // Flatten paginated results, then apply a client-side fallback filter that
  // drops any excluded role code (ADMIN/CLIENT). The fetch already excludes
  // these server-side; this guards against a backend that ignores the filter.
  // Requirements: 11.1, 11.2, 11.3
  const roles = useMemo<RoleOption[]>(
    () =>
      (data?.pages.flatMap((page) => page.content) ?? []).filter(
        (role) => !EXCLUDED_CODES.has(role.code),
      ),
    [data],
  )

  const selectedRole = useMemo(() => {
    if (value == null) return null
    return roles.find((role) => role.id === value) ?? null
  }, [roles, value])

  // Label shown on the trigger. When editing a user whose current role is
  // excluded (ADMIN/CLIENT), that role is absent from the options, so fall back
  // to the provided current-role name for context without offering it as a
  // selectable option. Requirement 11.4.
  const triggerLabel = selectedRole?.name ?? (value != null ? currentRoleName : undefined)

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
  }, [hasNextPage, isFetchingNextPage, fetchNextPage, roles.length, open])

  // --- Reset search when the popover closes ---
  function handleOpenChange(next: boolean) {
    setOpen(next)
    if (!next) setSearch('')
  }

  if (isError) {
    return (
      <div className="flex items-center gap-2">
        <div
          className={cn(
            'flex h-9 w-full items-center rounded-md border px-3 py-2 text-sm',
            'border-destructive bg-transparent text-destructive',
          )}
        >
          <AlertCircle className="mr-2 h-4 w-4 shrink-0" />
          <span className="truncate">{t('users.form.rolesLoadError')}</span>
        </div>
        <Button
          type="button"
          variant="outline"
          size="icon"
          onClick={() => refetch()}
          disabled={disabled}
          aria-label={t('users.form.rolesRetry')}
        >
          <RotateCcw className="h-4 w-4" />
        </Button>
      </div>
    )
  }

  return (
    <Popover open={open} onOpenChange={handleOpenChange}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="outline"
          role="combobox"
          aria-expanded={open}
          aria-label={t('users.form.role')}
          disabled={disabled}
          className={cn(
            'h-9 w-full justify-between font-normal',
            !triggerLabel && 'text-muted-foreground',
            error && 'border-destructive',
          )}
        >
          {triggerLabel ? (
            <span className="truncate">{triggerLabel}</span>
          ) : (
            <span>{t('users.form.rolePlaceholder')}</span>
          )}
          <ChevronsUpDown className="ml-2 h-4 w-4 shrink-0 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-[var(--radix-popover-trigger-width)] bg-popover p-0" align="start">
        <div className="flex flex-col">
          {/* Search input */}
          <div className="border-b px-3 py-2">
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={t('users.form.roleSearch')}
              className="h-8 w-full bg-transparent text-sm outline-none placeholder:text-muted-foreground"
              autoFocus
            />
          </div>

          {/* Roles list */}
          <div className="max-h-60 overflow-y-auto p-1">
            {isLoading ? (
              <div className="flex items-center justify-center px-2 py-4">
                <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
              </div>
            ) : roles.length === 0 ? (
              <div className="px-2 py-4 text-center text-sm text-muted-foreground">
                {t('users.form.rolesEmpty')}
              </div>
            ) : (
              <>
                {roles.map((role) => (
                  <button
                    key={role.id}
                    type="button"
                    onClick={() => {
                      onChange(role.id)
                      handleOpenChange(false)
                    }}
                    className={cn(
                      'relative flex w-full cursor-default select-none items-center rounded-sm py-1.5 pl-8 pr-2 text-sm outline-none',
                      'hover:bg-accent hover:text-accent-foreground',
                      'focus:bg-accent focus:text-accent-foreground',
                      role.id === value && 'bg-accent',
                    )}
                  >
                    <span className="absolute left-2 flex h-3.5 w-3.5 items-center justify-center">
                      {role.id === value && <Check className="h-4 w-4" />}
                    </span>
                    {role.name}
                  </button>
                ))}

                {/* Infinite-scroll sentinel + loading indicator */}
                <div ref={sentinelRef} />
                {isFetchingNextPage && (
                  <div className="flex items-center justify-center py-2">
                    <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </PopoverContent>
    </Popover>
  )
}
