import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'

import { CurrenciesList } from './components/CurrenciesList'
import { CurrencyFormSheet } from './components/CurrencyFormSheet'
import { DeleteCurrencyDialog } from './components/DeleteCurrencyDialog'
import type { CurrencyDto, CurrencyFormMode } from './types'

export interface FormSheetState {
  open: boolean
  mode: CurrencyFormMode
  currencyId: number | null
}

export interface DeleteDialogState {
  open: boolean
  currency: CurrencyDto | null
}

export default function CurrenciesPage() {
  const { t } = useTranslation()

  // Form sheet state — controls create/edit currency sheet overlay
  const [formSheet, setFormSheet] = useState<FormSheetState>({
    open: false,
    mode: 'create',
    currencyId: null,
  })

  // Delete confirmation dialog state
  const [deleteDialog, setDeleteDialog] = useState<DeleteDialogState>({
    open: false,
    currency: null,
  })

  const openCreateForm = () => {
    setFormSheet({ open: true, mode: 'create', currencyId: null })
  }

  const openEditForm = (currencyId: number) => {
    setFormSheet({ open: true, mode: 'edit', currencyId })
  }

  const closeFormSheet = () => {
    setFormSheet({ open: false, mode: 'create', currencyId: null })
  }

  const openDeleteDialog = (currency: CurrencyDto) => {
    setDeleteDialog({ open: true, currency })
  }

  const closeDeleteDialog = () => {
    setDeleteDialog({ open: false, currency: null })
  }

  const handleFormSuccess = () => {
    const mode = formSheet.mode
    closeFormSheet()
    toast.success(
      mode === 'create'
        ? t('currencies.toast.createSuccess')
        : t('currencies.toast.updateSuccess'),
    )
  }

  const handleDeleteSuccess = () => {
    closeDeleteDialog()
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-foreground">
        {t('currencies.pageTitle')}
      </h1>

      <CurrenciesList
        onCreateCurrency={openCreateForm}
        onEditCurrency={openEditForm}
        onDeleteCurrency={openDeleteDialog}
      />

      {/* Currency Form Sheet (create/edit) */}
      <CurrencyFormSheet
        open={formSheet.open}
        mode={formSheet.mode}
        currencyId={formSheet.currencyId}
        onClose={closeFormSheet}
        onSuccess={handleFormSuccess}
      />

      {/* Delete Confirmation Dialog */}
      <DeleteCurrencyDialog
        open={deleteDialog.open}
        currency={deleteDialog.currency}
        onClose={closeDeleteDialog}
        onSuccess={handleDeleteSuccess}
      />
    </div>
  )
}
