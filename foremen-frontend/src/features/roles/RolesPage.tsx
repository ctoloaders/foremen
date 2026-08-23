import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'

import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { RolesListTab } from './components/RolesListTab'
import { PermissionMatrixTab } from './components/PermissionMatrixTab'
import { RoleFormSheet } from './components/RoleFormSheet'
import { DeleteRoleDialog } from './components/DeleteRoleDialog'
import type { RoleDto, RoleFormMode } from './types'

export interface FormSheetState {
  open: boolean
  mode: RoleFormMode
  roleId: number | null
}

export interface DeleteDialogState {
  open: boolean
  role: RoleDto | null
}

export default function RolesPage() {
  const { t } = useTranslation()

  const [activeTab, setActiveTab] = useState<string>('list')

  // Form sheet state — controls create/edit role sheet overlay
  const [formSheet, setFormSheet] = useState<FormSheetState>({
    open: false,
    mode: 'create',
    roleId: null,
  })

  // Delete confirmation dialog state
  const [deleteDialog, setDeleteDialog] = useState<DeleteDialogState>({
    open: false,
    role: null,
  })

  // Handlers passed to child components
  const openCreateForm = () => {
    setFormSheet({ open: true, mode: 'create', roleId: null })
  }

  const openEditForm = (roleId: number) => {
    setFormSheet({ open: true, mode: 'edit', roleId })
  }

  const closeFormSheet = () => {
    setFormSheet({ open: false, mode: 'create', roleId: null })
  }

  const openDeleteDialog = (role: RoleDto) => {
    setDeleteDialog({ open: true, role })
  }

  const closeDeleteDialog = () => {
    setDeleteDialog({ open: false, role: null })
  }

  const handleFormSuccess = () => {
    closeFormSheet()
    toast.success(
      formSheet.mode === 'create'
        ? t('roles.toast.createSuccess')
        : t('roles.toast.updateSuccess'),
    )
  }

  const handleDeleteSuccess = () => {
    closeDeleteDialog()
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-foreground">
        {t('roles.pageTitle')}
      </h1>

      <Tabs value={activeTab} onValueChange={setActiveTab}>
        <TabsList>
          <TabsTrigger value="list">{t('roles.tabs.list')}</TabsTrigger>
          <TabsTrigger value="matrix">{t('roles.tabs.matrix')}</TabsTrigger>
        </TabsList>

        <TabsContent value="list">
          <RolesListTab
            onCreateRole={openCreateForm}
            onEditRole={openEditForm}
            onDeleteRole={openDeleteDialog}
          />
        </TabsContent>

        <TabsContent value="matrix">
          <PermissionMatrixTab />
        </TabsContent>
      </Tabs>

      {/* Role Form Sheet (create/edit) */}
      <RoleFormSheet
        open={formSheet.open}
        mode={formSheet.mode}
        roleId={formSheet.roleId}
        onClose={closeFormSheet}
        onSuccess={handleFormSuccess}
      />

      {/* Delete Role Confirmation Dialog */}
      <DeleteRoleDialog
        open={deleteDialog.open}
        role={deleteDialog.role}
        onClose={closeDeleteDialog}
        onSuccess={handleDeleteSuccess}
      />
    </div>
  )
}
