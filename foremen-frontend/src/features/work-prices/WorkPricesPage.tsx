import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'

import { WorkPricesList } from './components/WorkPricesList'
import { WorkPriceFormSheet } from './components/WorkPriceFormSheet'
import { DeleteWorkPriceDialog } from './components/DeleteWorkPriceDialog'
import type { WorkPriceDto, WorkPriceFormMode } from './types'

export interface FormSheetState {
  open: boolean
  mode: WorkPriceFormMode
  priceId: number | null
}

export interface DeleteDialogState {
  open: boolean
  price: WorkPriceDto | null
}

export default function WorkPricesPage() {
  const { t } = useTranslation()

  const [formSheet, setFormSheet] = useState<FormSheetState>({
    open: false,
    mode: 'create',
    priceId: null,
  })

  const [deleteDialog, setDeleteDialog] = useState<DeleteDialogState>({
    open: false,
    price: null,
  })

  const openCreateForm = () => {
    setFormSheet({ open: true, mode: 'create', priceId: null })
  }

  const openEditForm = (priceId: number) => {
    setFormSheet({ open: true, mode: 'edit', priceId })
  }

  const closeFormSheet = () => {
    setFormSheet({ open: false, mode: 'create', priceId: null })
  }

  const openDeleteDialog = (price: WorkPriceDto) => {
    setDeleteDialog({ open: true, price })
  }

  const closeDeleteDialog = () => {
    setDeleteDialog({ open: false, price: null })
  }

  const handleFormSuccess = () => {
    const mode = formSheet.mode
    closeFormSheet()
    toast.success(
      mode === 'create'
        ? t('workPrices.toast.createSuccess')
        : t('workPrices.toast.updateSuccess'),
    )
  }

  const handleDeleteSuccess = () => {
    closeDeleteDialog()
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-foreground">
        {t('workPrices.pageTitle')}
      </h1>

      <WorkPricesList
        onCreatePrice={openCreateForm}
        onEditPrice={openEditForm}
        onDeletePrice={openDeleteDialog}
      />

      <WorkPriceFormSheet
        open={formSheet.open}
        mode={formSheet.mode}
        priceId={formSheet.priceId}
        onClose={closeFormSheet}
        onSuccess={handleFormSuccess}
      />

      <DeleteWorkPriceDialog
        open={deleteDialog.open}
        price={deleteDialog.price}
        onClose={closeDeleteDialog}
        onSuccess={handleDeleteSuccess}
      />
    </div>
  )
}
