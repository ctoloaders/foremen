import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Plus, Pencil, UserX } from 'lucide-react'

import { DataTable } from '@/components/data-table'
import type { ColumnConfig } from '@/components/data-table'
import { usePermission } from '@/hooks/usePermission'
import { Button } from '@/components/ui/button'
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip'
import { ActiveBadge } from './components/ActiveBadge'
import { fetchUsers } from './api/users-api'
import type { UserDto, UserFormMode } from './types'

// Placeholder imports — components will be created in tasks 5.1 and 6.1
import { UserFormSheet } from './components/UserFormSheet'
import { DeactivateUserDialog } from './components/DeactivateUserDialog'

export interface FormSheetState {
  open: boolean
  mode: UserFormMode
  userId: number | null
}

export interface DeactivateDialogState {
  open: boolean
  user: UserDto | null
}

const columns: ColumnConfig<UserDto>[] = [
  {
    field: 'name',
    headerKey: 'users.table.name',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '150px',
  },
  {
    field: 'email',
    headerKey: 'users.table.email',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '200px',
  },
  {
    field: 'roleName',
    headerKey: 'users.table.role',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '120px',
  },
  {
    field: 'active',
    headerKey: 'users.table.active',
    dataType: 'boolean',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '100px',
    render: (value) => <ActiveBadge active={value as boolean} />,
  },
]

export default function UsersPage() {
  const { t } = useTranslation()

  // Permission gating for the USERS resource (FOR-03-07, Req 6.2/6.3/6.4).
  const { hasPermission } = usePermission()
  const canCreate = hasPermission('USERS', 'CREATE')
  const canUpdate = hasPermission('USERS', 'UPDATE')
  const canDelete = hasPermission('USERS', 'DELETE')

  // Form sheet state — controls create/edit user sheet overlay
  const [formSheet, setFormSheet] = useState<FormSheetState>({
    open: false,
    mode: 'create',
    userId: null,
  })

  // Deactivate confirmation dialog state
  const [deactivateDialog, setDeactivateDialog] = useState<DeactivateDialogState>({
    open: false,
    user: null,
  })

  // Handlers
  const openCreateForm = () => {
    setFormSheet({ open: true, mode: 'create', userId: null })
  }

  const openEditForm = (userId: number) => {
    setFormSheet({ open: true, mode: 'edit', userId })
  }

  const closeFormSheet = () => {
    setFormSheet({ open: false, mode: 'create', userId: null })
  }

  const openDeactivateDialog = (user: UserDto) => {
    setDeactivateDialog({ open: true, user })
  }

  const closeDeactivateDialog = () => {
    setDeactivateDialog({ open: false, user: null })
  }

  const handleFormSuccess = () => {
    closeFormSheet()
    toast.success(
      formSheet.mode === 'create'
        ? t('users.toast.createSuccess')
        : t('users.toast.updateSuccess'),
    )
  }

  const handleDeactivateSuccess = () => {
    closeDeactivateDialog()
    toast.success(t('users.toast.deactivateSuccess'))
  }

  // Row actions: edit (UPDATE) and deactivate (DELETE) buttons, each gated by
  // permission. Returns null when neither is permitted so DataTable collapses
  // the row-actions column entirely (FOR-03-07, Req 6.3/6.4/6.6).
  const rowActions = useCallback(
    (user: UserDto) => {
      if (!canUpdate && !canDelete) return null
      return (
        <div className="flex items-center gap-1">
          {canUpdate && (
            <Button
              variant="ghost"
              size="icon"
              onClick={(e) => {
                e.stopPropagation()
                openEditForm(user.id)
              }}
              aria-label={t('common.edit')}
            >
              <Pencil className="h-4 w-4" />
            </Button>
          )}

          {canDelete &&
            (user.active ? (
              <Button
                variant="ghost"
                size="icon"
                onClick={(e) => {
                  e.stopPropagation()
                  openDeactivateDialog(user)
                }}
                aria-label={t('users.actions.deactivate')}
                className="text-destructive hover:text-destructive"
              >
                <UserX className="h-4 w-4" />
              </Button>
            ) : (
              <TooltipProvider>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <span tabIndex={0}>
                      <Button
                        variant="ghost"
                        size="icon"
                        disabled
                        aria-label={t('users.actions.deactivate')}
                        className="text-destructive hover:text-destructive"
                      >
                        <UserX className="h-4 w-4" />
                      </Button>
                    </span>
                  </TooltipTrigger>
                  <TooltipContent>
                    {t('users.actions.alreadyInactive')}
                  </TooltipContent>
                </Tooltip>
              </TooltipProvider>
            ))}
        </div>
      )
    },
    [canUpdate, canDelete, t],
  )

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-foreground">
        {t('users.pageTitle')}
      </h1>

      <div className="space-y-4">
        {/* Create button above the table — rendered only with CREATE (Req 6.2) */}
        {canCreate && (
          <div className="flex justify-end">
            <Button onClick={openCreateForm}>
              <Plus className="mr-2 h-4 w-4" />
              {t('users.actions.create')}
            </Button>
          </div>
        )}

        {/* DataTable with full search, sort, filter, pagination */}
        <DataTable<UserDto>
          entityKey="users"
          resource="USERS"
          columns={columns}
          fetchFn={fetchUsers}
          defaultPageSize={25}
          pageSizeOptions={[10, 25, 50]}
          onRowClick={(user) => openEditForm(user.id)}
          rowActions={rowActions}
          showAuditButton={true}
        />
      </div>

      {/* User Form Sheet (create/edit) */}
      <UserFormSheet
        open={formSheet.open}
        mode={formSheet.mode}
        userId={formSheet.userId}
        onClose={closeFormSheet}
        onSuccess={handleFormSuccess}
      />

      {/* Deactivate User Confirmation Dialog */}
      <DeactivateUserDialog
        open={deactivateDialog.open}
        user={deactivateDialog.user}
        onClose={closeDeactivateDialog}
        onSuccess={handleDeactivateSuccess}
      />
    </div>
  )
}
