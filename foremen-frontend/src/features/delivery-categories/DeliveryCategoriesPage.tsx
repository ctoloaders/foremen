import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'

import { DeliveryCategoriesList } from './components/DeliveryCategoriesList'
import { DeliveryCategoryFormSheet } from './components/DeliveryCategoryFormSheet'
import { DeleteDeliveryCategoryDialog } from './components/DeleteDeliveryCategoryDialog'
import type { DeliveryCategoryDto, DeliveryCategoryFormMode } from './types'

export interface FormSheetState {
  open: boolean
  mode: DeliveryCategoryFormMode
  deliveryCategoryId: number | null
}

export interface DeleteDialogState {
  open: boolean
  deliveryCategory: DeliveryCategoryDto | null
}

export default function DeliveryCategoriesPage() {
  const { t } = useTranslation()

  // Form sheet state — controls create/edit delivery category sheet overlay
  const [formSheet, setFormSheet] = useState<FormSheetState>({
    open: false,
    mode: 'create',
    deliveryCategoryId: null,
  })

  // Delete confirmation dialog state
  const [deleteDialog, setDeleteDialog] = useState<DeleteDialogState>({
    open: false,
    deliveryCategory: null,
  })

  const openCreateForm = () => {
    setFormSheet({ open: true, mode: 'create', deliveryCategoryId: null })
  }

  const openEditForm = (deliveryCategoryId: number) => {
    setFormSheet({ open: true, mode: 'edit', deliveryCategoryId })
  }

  const closeFormSheet = () => {
    setFormSheet({ open: false, mode: 'create', deliveryCategoryId: null })
  }

  const openDeleteDialog = (deliveryCategory: DeliveryCategoryDto) => {
    setDeleteDialog({ open: true, deliveryCategory })
  }

  const closeDeleteDialog = () => {
    setDeleteDialog({ open: false, deliveryCategory: null })
  }

  const handleFormSuccess = () => {
    const mode = formSheet.mode
    closeFormSheet()
    toast.success(
      mode === 'create'
        ? t('deliveryCategories.toast.createSuccess')
        : t('deliveryCategories.toast.updateSuccess'),
    )
  }

  const handleDeleteSuccess = () => {
    closeDeleteDialog()
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-foreground">
        {t('deliveryCategories.pageTitle')}
      </h1>

      <DeliveryCategoriesList
        onCreateDeliveryCategory={openCreateForm}
        onEditDeliveryCategory={openEditForm}
        onDeleteDeliveryCategory={openDeleteDialog}
      />

      {/* Delivery Category Form Sheet (create/edit) */}
      <DeliveryCategoryFormSheet
        open={formSheet.open}
        mode={formSheet.mode}
        deliveryCategoryId={formSheet.deliveryCategoryId}
        onClose={closeFormSheet}
        onSuccess={handleFormSuccess}
      />

      {/* Delete Confirmation Dialog */}
      <DeleteDeliveryCategoryDialog
        open={deleteDialog.open}
        deliveryCategory={deleteDialog.deliveryCategory}
        onClose={closeDeleteDialog}
        onSuccess={handleDeleteSuccess}
      />
    </div>
  )
}
