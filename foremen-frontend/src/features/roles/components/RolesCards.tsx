import { useTranslation } from 'react-i18next'
import { Pencil, Trash2 } from 'lucide-react'

import { Badge } from '@/components/ui/badge'
import type { RoleDto } from '../types'

interface RolesCardsProps {
  roles: RoleDto[]
  onEditRole: (roleId: number) => void
  onDeleteRole: (role: RoleDto) => void
}

export function RolesCards({ roles, onEditRole, onDeleteRole }: RolesCardsProps) {
  const { t } = useTranslation()

  return (
    <div className="space-y-3">
      {roles.map((role) => (
        <div
          key={role.id}
          className="rounded-lg border border-border p-4 space-y-3"
        >
          <div className="flex items-center justify-between">
            <span className="font-medium text-foreground">{role.name}</span>
            {role.system && (
              <Badge variant="secondary">{t('roles.badge.system')}</Badge>
            )}
          </div>

          <p className="text-xs font-mono text-muted-foreground">{role.code}</p>

          {role.description && (
            <p className="text-sm text-muted-foreground">{role.description}</p>
          )}

          <div className="flex gap-2 pt-1">
            <button
              type="button"
              onClick={() => onEditRole(role.id)}
              className="inline-flex h-8 items-center gap-1.5 rounded-md px-3 text-sm text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            >
              <Pencil className="h-3.5 w-3.5" />
              {t('common.edit')}
            </button>
            <button
              type="button"
              onClick={() => onDeleteRole(role)}
              disabled={role.system}
              className="inline-flex h-8 items-center gap-1.5 rounded-md px-3 text-sm text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive disabled:pointer-events-none disabled:opacity-50"
              title={role.system ? t('roles.errors.systemDelete') : undefined}
            >
              <Trash2 className="h-3.5 w-3.5" />
              {t('common.delete')}
            </button>
          </div>
        </div>
      ))}
    </div>
  )
}
