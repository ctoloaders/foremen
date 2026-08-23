import { useTranslation } from 'react-i18next'
import { Pencil, Trash2 } from 'lucide-react'

import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Badge } from '@/components/ui/badge'
import type { RoleDto } from '../types'

interface RolesTableProps {
  roles: RoleDto[]
  onEditRole: (roleId: number) => void
  onDeleteRole: (role: RoleDto) => void
}

export function RolesTable({ roles, onEditRole, onDeleteRole }: RolesTableProps) {
  const { t } = useTranslation()

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>{t('roles.table.code')}</TableHead>
          <TableHead>{t('roles.table.name')}</TableHead>
          <TableHead>{t('roles.table.description')}</TableHead>
          <TableHead>{t('roles.table.system')}</TableHead>
          <TableHead className="text-right">{t('roles.table.actions')}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {roles.map((role) => (
          <TableRow key={role.id}>
            <TableCell className="font-mono text-xs">{role.code}</TableCell>
            <TableCell className="font-medium">{role.name}</TableCell>
            <TableCell className="text-muted-foreground">
              {role.description || '—'}
            </TableCell>
            <TableCell>
              {role.system && (
                <Badge variant="secondary">{t('roles.badge.system')}</Badge>
              )}
            </TableCell>
            <TableCell className="text-right">
              <div className="flex items-center justify-end gap-1">
                <button
                  type="button"
                  onClick={() => onEditRole(role.id)}
                  className="inline-flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                  aria-label={t('common.edit')}
                >
                  <Pencil className="h-4 w-4" />
                </button>
                <button
                  type="button"
                  onClick={() => onDeleteRole(role)}
                  disabled={role.system}
                  className="inline-flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive disabled:pointer-events-none disabled:opacity-50"
                  aria-label={t('common.delete')}
                  title={role.system ? t('roles.errors.systemDelete') : undefined}
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}
