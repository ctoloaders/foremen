import { useState, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, ChevronsUpDown, Loader2, AlertCircle, RotateCcw } from 'lucide-react'

import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { useRolesForSelect } from '../api/query-hooks'
import type { RoleOption } from '../types'

interface RoleSelectProps {
  value: number | undefined
  onChange: (value: number) => void
  error?: string
  disabled?: boolean
}

export function RoleSelect({ value, onChange, error, disabled }: RoleSelectProps) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')

  const { data: roles, isLoading, isError, refetch } = useRolesForSelect()

  const filteredRoles = useMemo(() => {
    if (!roles) return []
    if (!search.trim()) return roles
    const query = search.toLowerCase()
    return roles.filter((role: RoleOption) => role.name.toLowerCase().includes(query))
  }, [roles, search])

  const selectedRole = useMemo(() => {
    if (!roles || value == null) return null
    return roles.find((role: RoleOption) => role.id === value) ?? null
  }, [roles, value])

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
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="outline"
          role="combobox"
          aria-expanded={open}
          aria-label={t('users.form.role')}
          disabled={disabled || isLoading}
          className={cn(
            'h-9 w-full justify-between font-normal',
            !selectedRole && 'text-muted-foreground',
            error && 'border-destructive',
          )}
        >
          {isLoading ? (
            <span className="flex items-center gap-2">
              <Loader2 className="h-4 w-4 animate-spin" />
              {t('users.form.rolesLoading')}
            </span>
          ) : selectedRole ? (
            <span className="truncate">{selectedRole.name}</span>
          ) : (
            <span>{t('users.form.rolePlaceholder')}</span>
          )}
          <ChevronsUpDown className="ml-2 h-4 w-4 shrink-0 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-[var(--radix-popover-trigger-width)] p-0" align="start">
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
            {filteredRoles.length === 0 ? (
              <div className="px-2 py-4 text-center text-sm text-muted-foreground">
                {t('users.form.rolesEmpty')}
              </div>
            ) : (
              filteredRoles.map((role: RoleOption) => (
                <button
                  key={role.id}
                  type="button"
                  onClick={() => {
                    onChange(role.id)
                    setOpen(false)
                    setSearch('')
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
              ))
            )}
          </div>
        </div>
      </PopoverContent>
    </Popover>
  )
}
