import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'

import { OfferPackagesList } from './components/OfferPackagesList'
import { OfferPackageFormSheet } from './components/OfferPackageFormSheet'
import { DeleteOfferPackageDialog } from './components/DeleteOfferPackageDialog'
import type { OfferPackageDto, OfferPackageFormMode } from './types'

export interface FormSheetState {
  open: boolean
  mode: OfferPackageFormMode
  packageId: number | null
}

export interface DeleteDialogState {
  open: boolean
  offerPackage: OfferPackageDto | null
}

export default function OfferPackagesPage() {
  const { t } = useTranslation()

  // Form sheet state — controls create/edit package sheet overlay
  const [formSheet, setFormSheet] = useState<FormSheetState>({
    open: false,
    mode: 'create',
    packageId: null,
  })

  // Delete confirmation dialog state
  const [deleteDialog, setDeleteDialog] = useState<DeleteDialogState>({
    open: false,
    offerPackage: null,
  })

  const openCreateForm = () => {
    setFormSheet({ open: true, mode: 'create', packageId: null })
  }

  const openEditForm = (packageId: number) => {
    setFormSheet({ open: true, mode: 'edit', packageId })
  }

  const closeFormSheet = () => {
    setFormSheet({ open: false, mode: 'create', packageId: null })
  }

  const openDeleteDialog = (offerPackage: OfferPackageDto) => {
    setDeleteDialog({ open: true, offerPackage })
  }

  const closeDeleteDialog = () => {
    setDeleteDialog({ open: false, offerPackage: null })
  }

  const handleFormSuccess = () => {
    const mode = formSheet.mode
    closeFormSheet()
    toast.success(
      mode === 'create'
        ? t('offerPackages.toast.createSuccess')
        : t('offerPackages.toast.updateSuccess'),
    )
  }

  const handleDeleteSuccess = () => {
    closeDeleteDialog()
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-foreground">
        {t('offerPackages.pageTitle')}
      </h1>

      <OfferPackagesList
        onCreatePackage={openCreateForm}
        onEditPackage={openEditForm}
        onDeletePackage={openDeleteDialog}
      />

      {/* Offer Package Form Sheet (create/edit) */}
      <OfferPackageFormSheet
        open={formSheet.open}
        mode={formSheet.mode}
        packageId={formSheet.packageId}
        onClose={closeFormSheet}
        onSuccess={handleFormSuccess}
      />

      {/* Delete Confirmation Dialog */}
      <DeleteOfferPackageDialog
        open={deleteDialog.open}
        offerPackage={deleteDialog.offerPackage}
        onClose={closeDeleteDialog}
        onSuccess={handleDeleteSuccess}
      />
    </div>
  )
}
