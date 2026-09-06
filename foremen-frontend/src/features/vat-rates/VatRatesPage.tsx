import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'

import { VatRatesList } from './components/VatRatesList'
import { VatRateFormSheet } from './components/VatRateFormSheet'
import { DeleteVatRateDialog } from './components/DeleteVatRateDialog'
import type { VatRateDto, VatRateFormMode } from './types'

export interface FormSheetState {
  open: boolean
  mode: VatRateFormMode
  vatRateId: number | null
}

export interface DeleteDialogState {
  open: boolean
  vatRate: VatRateDto | null
}

export default function VatRatesPage() {
  const { t } = useTranslation()

  // Form sheet state — controls create/edit VAT rate sheet overlay
  const [formSheet, setFormSheet] = useState<FormSheetState>({
    open: false,
    mode: 'create',
    vatRateId: null,
  })

  // Delete confirmation dialog state
  const [deleteDialog, setDeleteDialog] = useState<DeleteDialogState>({
    open: false,
    vatRate: null,
  })

  const openCreateForm = () => {
    setFormSheet({ open: true, mode: 'create', vatRateId: null })
  }

  const openEditForm = (vatRateId: number) => {
    setFormSheet({ open: true, mode: 'edit', vatRateId })
  }

  const closeFormSheet = () => {
    setFormSheet({ open: false, mode: 'create', vatRateId: null })
  }

  const openDeleteDialog = (vatRate: VatRateDto) => {
    setDeleteDialog({ open: true, vatRate })
  }

  const closeDeleteDialog = () => {
    setDeleteDialog({ open: false, vatRate: null })
  }

  const handleFormSuccess = () => {
    const mode = formSheet.mode
    closeFormSheet()
    toast.success(
      mode === 'create'
        ? t('vatRates.toast.createSuccess')
        : t('vatRates.toast.updateSuccess'),
    )
  }

  const handleDeleteSuccess = () => {
    closeDeleteDialog()
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-foreground">
        {t('vatRates.pageTitle')}
      </h1>

      <VatRatesList
        onCreateVatRate={openCreateForm}
        onEditVatRate={openEditForm}
        onDeleteVatRate={openDeleteDialog}
      />

      {/* VAT rate Form Sheet (create/edit) */}
      <VatRateFormSheet
        open={formSheet.open}
        mode={formSheet.mode}
        vatRateId={formSheet.vatRateId}
        onClose={closeFormSheet}
        onSuccess={handleFormSuccess}
      />

      {/* Delete Confirmation Dialog */}
      <DeleteVatRateDialog
        open={deleteDialog.open}
        vatRate={deleteDialog.vatRate}
        onClose={closeDeleteDialog}
        onSuccess={handleDeleteSuccess}
      />
    </div>
  )
}
