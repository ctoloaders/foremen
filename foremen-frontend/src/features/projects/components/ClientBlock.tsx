/**
 * ClientBlock — the optional "client" section of the project create form
 * (FOR-04-13 Req 8.4).
 *
 * Toggles between two mutually exclusive modes:
 *  - "existing": pick an already-registered CLIENT user via a searchable,
 *    infinite-scroll select filtered server-side to `role.code==CLIENT`.
 *  - "new": add a new client through the reused invite fields (name, phone,
 *    email). The client role is NOT selectable here — the server always fixes
 *    it to CLIENT — so there is deliberately no role selector.
 *
 * The block is controlled: `value` is a {@link ClientBlockValue} (existing id,
 * new-client payload, or `null` for "no client"). Switching mode clears the
 * other branch's value. The owning form validates and submits.
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useInfiniteQuery } from '@tanstack/react-query'
import { Check, ChevronsUpDown, Loader2 } from 'lucide-react'

import { cn } from '@/lib/utils'
import { useDebounce } from '@/hooks/useDebounce'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { apiRequest } from '@/lib/api-client'
import { PhoneInput } from '@/features/users/components/PhoneInput'
import type { PaginatedResponse } from '@/components/data-table'
import type { UserDto } from '@/features/users/types'
import type {
  ClientBlockMode,
  ClientBlockValue,
  NewClientInput,
} from '../types'

/** Page size for the existing-client options query. */
const PAGE_SIZE = 20

/** RSQL fragment restricting the users list to CLIENT-role users. */
const CLIENT_ROLE_CLAUSE = 'role.code==CLIENT'

// --- Existing CLIENT user select ---------------------------------------------

interface ExistingClientSelectProps {
  value: number | undefined
  onChange: (userId: number) => void
  disabled?: boolean
  error?: string
}

/**
 * Searchable, infinite-scroll select of existing CLIENT users. Fetches
 * `GET /api/users` filtered server-side to `role.code==CLIENT`, combined with a
 * `name~ct~<search>` term when the user types.
 */
function ExistingClientSelect({
  value,
  onChange,
  disabled,
  error,
}: ExistingClientSelectProps) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const debouncedSearch = useDebounce(search, 300)

  const { data, isLoading, fetchNextPage, hasNextPage, isFetchingNextPage } =
    useInfiniteQuery({
      queryKey: ['project-client-users', debouncedSearch.trim()],
      queryFn: ({ pageParam = 0 }) => {
        const term = debouncedSearch.trim()
        const query = term ? `name~ct~${term} AND ${CLIENT_ROLE_CLAUSE}` : CLIENT_ROLE_CLAUSE
        const params = new URLSearchParams()
        params.set('page', String(pageParam))
        params.set('size', String(PAGE_SIZE))
        params.set('sort', 'name,asc')
        // Keep the literal `~`/`==` RSQL operators on the wire.
        let url = `/api/users?${params}&query=${encodeURIComponent(query).replace(/%7E/gi, '~')}`
        return apiRequest<PaginatedResponse<UserDto>>(url)
      },
      initialPageParam: 0,
      getNextPageParam: (lastPage) =>
        lastPage.last ? undefined : lastPage.number + 1,
      staleTime: 60_000,
    })

  const clients = useMemo<UserDto[]>(
    () => data?.pages.flatMap((page) => page.content) ?? [],
    [data],
  )

  const selected = useMemo(
    () => (value == null ? null : clients.find((c) => c.id === value) ?? null),
    [clients, value],
  )

  const sentinelRef = useRef<HTMLDivElement | null>(null)
  useEffect(() => {
    const sentinel = sentinelRef.current
    if (!sentinel || !hasNextPage) return
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
  }, [hasNextPage, isFetchingNextPage, fetchNextPage, clients.length, open])

  function handleOpenChange(next: boolean) {
    setOpen(next)
    if (!next) setSearch('')
  }

  return (
    <Popover open={open} onOpenChange={handleOpenChange}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="outline"
          role="combobox"
          aria-expanded={open}
          aria-label={t('projects.form.client.existingLabel')}
          disabled={disabled}
          className={cn(
            'h-9 w-full justify-between font-normal',
            !selected && 'text-muted-foreground',
            error && 'border-destructive',
          )}
        >
          {selected ? (
            <span className="truncate">{selected.name}</span>
          ) : (
            <span>{t('projects.form.client.existingPlaceholder')}</span>
          )}
          <ChevronsUpDown className="ml-2 h-4 w-4 shrink-0 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent
        className="w-[var(--radix-popover-trigger-width)] bg-popover p-0"
        align="start"
      >
        <div className="flex flex-col">
          <div className="border-b px-3 py-2">
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={t('projects.form.client.existingSearch')}
              className="h-8 w-full bg-transparent text-sm outline-none placeholder:text-muted-foreground"
              autoFocus
            />
          </div>
          <div className="max-h-60 overflow-y-auto p-1">
            {isLoading ? (
              <div className="flex items-center justify-center px-2 py-4">
                <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
              </div>
            ) : clients.length === 0 ? (
              <div className="px-2 py-4 text-center text-sm text-muted-foreground">
                {t('projects.form.client.existingEmpty')}
              </div>
            ) : (
              <>
                {clients.map((client) => (
                  <button
                    key={client.id}
                    type="button"
                    onClick={() => {
                      onChange(client.id)
                      handleOpenChange(false)
                    }}
                    className={cn(
                      'relative flex w-full cursor-default select-none items-center rounded-sm py-1.5 pl-8 pr-2 text-sm outline-none',
                      'hover:bg-accent hover:text-accent-foreground',
                      'focus:bg-accent focus:text-accent-foreground',
                      client.id === value && 'bg-accent',
                    )}
                  >
                    <span className="absolute left-2 flex h-3.5 w-3.5 items-center justify-center">
                      {client.id === value && <Check className="h-4 w-4" />}
                    </span>
                    <span className="truncate">{client.name}</span>
                  </button>
                ))}
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

// --- New client invite fields ------------------------------------------------

interface NewClientFieldsProps {
  value: NewClientInput
  onChange: (value: NewClientInput) => void
  disabled?: boolean
  errors?: Partial<Record<'name' | 'email' | 'phone', string>>
}

/**
 * The reused invite fields for a brand-new client: name, email, phone. NO role
 * selector — the server fixes the role to CLIENT (Req 8.4).
 */
function NewClientFields({ value, onChange, disabled, errors }: NewClientFieldsProps) {
  const { t } = useTranslation()

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        {/* Name */}
        <div className="space-y-2">
          <label
            htmlFor="client-name"
            className="text-sm font-medium text-foreground"
          >
            {t('projects.form.client.name')}
          </label>
          <input
            id="client-name"
            type="text"
            value={value.name}
            disabled={disabled}
            onChange={(e) => onChange({ ...value, name: e.target.value })}
            className={cn(
              'h-9 w-full rounded-md border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50',
              errors?.name ? 'border-destructive' : 'border-border',
            )}
          />
          {errors?.name && (
            <p className="text-xs text-destructive">{errors.name}</p>
          )}
        </div>

        {/* Email */}
        <div className="space-y-2">
          <label
            htmlFor="client-email"
            className="text-sm font-medium text-foreground"
          >
            {t('projects.form.client.email')}
          </label>
          <input
            id="client-email"
            type="email"
            value={value.email}
            disabled={disabled}
            onChange={(e) => onChange({ ...value, email: e.target.value })}
            className={cn(
              'h-9 w-full rounded-md border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50',
              errors?.email ? 'border-destructive' : 'border-border',
            )}
          />
          {errors?.email && (
            <p className="text-xs text-destructive">{errors.email}</p>
          )}
        </div>
      </div>

      {/* Phone (optional) */}
      <div className="space-y-2">
        <label
          htmlFor="client-phone"
          className="text-sm font-medium text-foreground"
        >
          {t('projects.form.client.phone')}
        </label>
        <PhoneInput
          value={value.phone ?? ''}
          onChange={(val) => onChange({ ...value, phone: val ?? '' })}
          defaultCountry="PL"
          error={errors?.phone}
          disabled={disabled}
        />
      </div>
    </div>
  )
}

// --- ClientBlock -------------------------------------------------------------

const EMPTY_NEW_CLIENT: NewClientInput = { name: '', email: '', phone: '' }

export interface ClientBlockProps {
  /** Current client selection (existing id, new-client payload, or null). */
  value: ClientBlockValue
  /** Emit the new client selection. */
  onChange: (value: ClientBlockValue) => void
  disabled?: boolean
  /** Field-level errors for the new-client invite fields. */
  newClientErrors?: Partial<Record<'name' | 'email' | 'phone', string>>
  /** Error for the existing-client select. */
  existingError?: string
}

export function ClientBlock({
  value,
  onChange,
  disabled,
  newClientErrors,
  existingError,
}: ClientBlockProps) {
  const { t } = useTranslation()

  // Derive the active mode from the current value; default to "existing".
  const [mode, setMode] = useState<ClientBlockMode>(
    value?.kind === 'new' ? 'new' : 'existing',
  )

  // Preserve the last-entered new-client draft while the "existing" tab is
  // active, so switching back does not wipe typed fields.
  const newClientDraft = value?.kind === 'new' ? value.newClient : EMPTY_NEW_CLIENT
  const existingId = value?.kind === 'existing' ? value.existingClientUserId : undefined

  function switchMode(next: ClientBlockMode) {
    if (next === mode) return
    setMode(next)
    // Switching mode clears the other branch's value (Req 8.4 mutual exclusion).
    onChange(null)
  }

  return (
    <div className="space-y-4 rounded-md border border-border p-4">
      <div className="space-y-1">
        <p className="text-sm font-medium text-foreground">
          {t('projects.form.client.title')}
        </p>
        <p className="text-xs text-muted-foreground">
          {t('projects.form.client.description')}
        </p>
      </div>

      {/* Mode toggle: existing vs new */}
      <div
        role="radiogroup"
        aria-label={t('projects.form.client.title')}
        className="inline-flex rounded-md border border-border p-0.5"
      >
        <button
          type="button"
          role="radio"
          aria-checked={mode === 'existing'}
          disabled={disabled}
          onClick={() => switchMode('existing')}
          className={cn(
            'rounded-sm px-3 py-1.5 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50',
            mode === 'existing'
              ? 'bg-primary text-primary-foreground'
              : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {t('projects.form.client.existingTab')}
        </button>
        <button
          type="button"
          role="radio"
          aria-checked={mode === 'new'}
          disabled={disabled}
          onClick={() => switchMode('new')}
          className={cn(
            'rounded-sm px-3 py-1.5 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50',
            mode === 'new'
              ? 'bg-primary text-primary-foreground'
              : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {t('projects.form.client.newTab')}
        </button>
      </div>

      {mode === 'existing' ? (
        <div className="space-y-2">
          <label className="text-sm font-medium text-foreground">
            {t('projects.form.client.existingLabel')}
          </label>
          <ExistingClientSelect
            value={existingId}
            disabled={disabled}
            error={existingError}
            onChange={(userId) =>
              onChange({ kind: 'existing', existingClientUserId: userId })
            }
          />
        </div>
      ) : (
        <NewClientFields
          value={newClientDraft}
          disabled={disabled}
          errors={newClientErrors}
          onChange={(next) => onChange({ kind: 'new', newClient: next })}
        />
      )}
    </div>
  )
}
