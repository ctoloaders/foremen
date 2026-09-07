/**
 * TeamMemberSelect — the project create form's team multi-select (FOR-04-13 Req 8.4).
 *
 * Lets the user pick one or more team members from `GET /api/users` (searchable,
 * infinite-scroll). Each selected user is assigned to the project under a
 * `projectRoleId` that, by default, equals the user's company role — the role is
 * SHOWN, not separately chosen (Req 2.4, 8.4). Because the users LIST DTO exposes
 * only `roleName` (not `roleId`), the component resolves each picked user's
 * company `roleId` by fetching that user's detail (`GET /api/users/{id}` →
 * `UserExtendedDto.roleId`) once, then keeps the resolved `{ userId, userName,
 * roleId, roleName }` in the controlled selection the owning form maps into
 * `CreateProjectRequest.members`.
 *
 * Controlled: `value` is the resolved selection, `onChange` emits the new one.
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useInfiniteQuery } from '@tanstack/react-query'
import { Check, ChevronsUpDown, Loader2, X } from 'lucide-react'

import { cn } from '@/lib/utils'
import { useDebounce } from '@/hooks/useDebounce'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { apiRequest } from '@/lib/api-client'
import type { PaginatedResponse } from '@/components/data-table'
import type { UserDto, UserExtendedDto } from '@/features/users/types'

/** Page size for the team-member options query. */
const PAGE_SIZE = 20

/**
 * A resolved team-member selection: the user plus the company role (`roleId`)
 * under which they are assigned to the project. `roleName` is carried for
 * display only (the role is shown, not chosen).
 */
export interface SelectedTeamMember {
  userId: number
  userName: string
  roleId: number
  roleName: string
}

export interface TeamMemberSelectProps {
  /** Currently selected team members (controlled). */
  value: SelectedTeamMember[]
  /** Emit the new selection. */
  onChange: (members: SelectedTeamMember[]) => void
  disabled?: boolean
}

export function TeamMemberSelect({ value, onChange, disabled }: Readonly<TeamMemberSelectProps>) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [resolvingId, setResolvingId] = useState<number | null>(null)
  const debouncedSearch = useDebounce(search, 300)

  const { data, isLoading, fetchNextPage, hasNextPage, isFetchingNextPage } =
    useInfiniteQuery({
      queryKey: ['project-team-users', debouncedSearch.trim()],
      queryFn: ({ pageParam = 0 }) => {
        const term = debouncedSearch.trim()
        const params = new URLSearchParams()
        params.set('page', String(pageParam))
        params.set('size', String(PAGE_SIZE))
        params.set('sort', 'name,asc')
        let url = `/api/users?${params}`
        if (term) {
          url += `&query=${encodeURIComponent(`name~ct~${term}`).replace(/%7E/gi, '~')}`
        }
        return apiRequest<PaginatedResponse<UserDto>>(url)
      },
      initialPageParam: 0,
      getNextPageParam: (lastPage) => (lastPage.last ? undefined : lastPage.number + 1),
      staleTime: 60_000,
    })

  const users = useMemo<UserDto[]>(
    () => data?.pages.flatMap((page) => page.content) ?? [],
    [data],
  )

  const selectedIds = useMemo(() => new Set(value.map((m) => m.userId)), [value])

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
  }, [hasNextPage, isFetchingNextPage, fetchNextPage, users.length, open])

  function handleOpenChange(next: boolean) {
    setOpen(next)
    if (!next) setSearch('')
  }

  async function toggleUser(user: UserDto) {
    if (selectedIds.has(user.id)) {
      onChange(value.filter((m) => m.userId !== user.id))
      return
    }
    // Resolve the user's company role id (LIST DTO carries only roleName).
    setResolvingId(user.id)
    try {
      const detail = await apiRequest<UserExtendedDto>(`/api/users/${user.id}`)
      onChange([
        ...value,
        {
          userId: user.id,
          userName: user.name,
          roleId: detail.roleId,
          roleName: detail.roleName ?? user.roleName,
        },
      ])
    } finally {
      setResolvingId(null)
    }
  }

  function removeMember(userId: number) {
    onChange(value.filter((m) => m.userId !== userId))
  }

  return (
    <div className="space-y-2" data-testid="project-team-select">
      {/* Selected member chips */}
      {value.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {value.map((member) => (
            <span
              key={member.userId}
              className="inline-flex max-w-full items-center gap-1 rounded-full bg-secondary px-2 py-0.5 text-xs text-secondary-foreground"
            >
              <span className="truncate">
                {member.userName} — {member.roleName}
              </span>
              <button
                type="button"
                disabled={disabled}
                onClick={() => removeMember(member.userId)}
                aria-label={t('common.delete')}
                className="shrink-0 hover:text-foreground disabled:opacity-50"
              >
                <X className="h-3 w-3" />
              </button>
            </span>
          ))}
        </div>
      )}

      <Popover open={open} onOpenChange={handleOpenChange}>
        <PopoverTrigger asChild>
          <Button
            type="button"
            variant="outline"
            role="combobox"
            aria-expanded={open}
            aria-label={t('projects.form.team.label')}
            disabled={disabled}
            className="h-9 w-full justify-between font-normal text-muted-foreground"
          >
            <span>
              {value.length === 0
                ? t('projects.form.team.placeholder')
                : t('projects.form.team.selectedCount', { count: value.length })}
            </span>
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
                placeholder={t('projects.form.team.search')}
                className="h-8 w-full bg-transparent text-sm outline-none placeholder:text-muted-foreground"
                autoFocus
              />
            </div>
            <div className="max-h-60 overflow-y-auto p-1">
              {isLoading ? (
                <div className="flex items-center justify-center px-2 py-4">
                  <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
                </div>
              ) : users.length === 0 ? (
                <div className="px-2 py-4 text-center text-sm text-muted-foreground">
                  {t('projects.form.team.empty')}
                </div>
              ) : (
                <>
                  {users.map((user) => {
                    const isSelected = selectedIds.has(user.id)
                    const isResolving = resolvingId === user.id
                    return (
                      <button
                        key={user.id}
                        type="button"
                        disabled={isResolving}
                        onClick={() => toggleUser(user)}
                        className={cn(
                          'relative flex w-full cursor-default select-none items-center rounded-sm py-1.5 pl-8 pr-2 text-sm outline-none',
                          'hover:bg-accent hover:text-accent-foreground',
                          'focus:bg-accent focus:text-accent-foreground',
                          isSelected && 'bg-accent',
                        )}
                      >
                        <span className="absolute left-2 flex h-3.5 w-3.5 items-center justify-center">
                          {isResolving ? (
                            <Loader2 className="h-3.5 w-3.5 animate-spin" />
                          ) : (
                            isSelected && <Check className="h-4 w-4" />
                          )}
                        </span>
                        <span className="truncate">
                          {user.name} — {user.roleName}
                        </span>
                      </button>
                    )
                  })}
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
    </div>
  )
}
