import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'

import { DeliveryStatusesList } from './components/DeliveryStatusesList'
import { DeliveryStatusFormSheet } from './components/DeliveryStatusFormSheet'
import { DeleteDeliveryStatusDialog } from './components/DeleteDeliveryStatusDialog'
import type { DeliveryStatusDto, DeliveryStatusFormMode } from './types'

export interface FormSheetState {
  open: boolean
  mode: DeliveryStatusFormMode
  statusId: number | null
}

export interface DeleteDialogState {
  open: boolean
  status: DeliveryStatusDto | null
}

export default function DeliveryStatusesPage() {
  const { t } = useTranslation()

  // Form sheet state — controls create/edit status sheet overlay
  const [formSheet, setFormSheet] = useState<FormSheetState>({
    open: false,
    mode: 'create',
    statusId: null,
  })

  // Delete confirmation dialog state
  const [deleteDialog, setDeleteDialog] = useState<DeleteDialogState>({
    open: false,
    status: null,
  })

  const openCreateForm = () => {
    setFormSheet({ open: true, mode: 'create', statusId: null })
  }

  const openEditForm = (statusId: number) => {
    setFormSheet({ open: true, mode: 'edit', statusId })
  }

  const closeFormSheet = () => {
    setFormSheet({ open: false, mode: 'create', statusId: null })
  }

  const openDeleteDialog = (status: DeliveryStatusDto) => {
    setDeleteDialog({ open: true, status })
  }

  const closeDeleteDialog = () => {
    setDeleteDialog({ open: false, status: null })
  }

  const handleFormSuccess = () => {
    const mode = formSheet.mode
    closeFormSheet()
    toast.success(
      mode === 'create'
        ? t('deliveryStatuses.toast.createSuccess')
        : t('deliveryStatuses.toast.updateSuccess'),
    )
  }

  const handleDeleteSuccess = () => {
    closeDeleteDialog()
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-foreground">
        {t('deliveryStatuses.pageTitle')}
      </h1>

      <DeliveryStatusesList
        onCreateStatus={openCreateForm}
        onEditStatus={openEditForm}
        onDeleteStatus={openDeleteDialog}
      />

      {/* Delivery Status Form Sheet (create/edit) */}
      <DeliveryStatusFormSheet
        open={formSheet.open}
        mode={formSheet.mode}
        statusId={formSheet.statusId}
        onClose={closeFormSheet}
        onSuccess={handleFormSuccess}
      />

      {/* Delete Confirmation Dialog */}
      <DeleteDeliveryStatusDialog
        open={deleteDialog.open}
        status={deleteDialog.status}
        onClose={closeDeleteDialog}
        onSuccess={handleDeleteSuccess}
      />
    </div>
  )
}
