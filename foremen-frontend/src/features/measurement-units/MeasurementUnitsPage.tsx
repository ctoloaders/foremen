import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'

import { MeasurementUnitsList } from './components/MeasurementUnitsList'
import { MeasurementUnitFormSheet } from './components/MeasurementUnitFormSheet'
import { DeleteMeasurementUnitDialog } from './components/DeleteMeasurementUnitDialog'
import type { MeasurementUnitDto, MeasurementUnitFormMode } from './types'

export interface FormSheetState {
  open: boolean
  mode: MeasurementUnitFormMode
  unitId: number | null
}

export interface DeleteDialogState {
  open: boolean
  unit: MeasurementUnitDto | null
}

export default function MeasurementUnitsPage() {
  const { t } = useTranslation()

  // Form sheet state — controls create/edit unit sheet overlay
  const [formSheet, setFormSheet] = useState<FormSheetState>({
    open: false,
    mode: 'create',
    unitId: null,
  })

  // Delete confirmation dialog state
  const [deleteDialog, setDeleteDialog] = useState<DeleteDialogState>({
    open: false,
    unit: null,
  })

  const openCreateForm = () => {
    setFormSheet({ open: true, mode: 'create', unitId: null })
  }

  const openEditForm = (unitId: number) => {
    setFormSheet({ open: true, mode: 'edit', unitId })
  }

  const closeFormSheet = () => {
    setFormSheet({ open: false, mode: 'create', unitId: null })
  }

  const openDeleteDialog = (unit: MeasurementUnitDto) => {
    setDeleteDialog({ open: true, unit })
  }

  const closeDeleteDialog = () => {
    setDeleteDialog({ open: false, unit: null })
  }

  const handleFormSuccess = () => {
    const mode = formSheet.mode
    closeFormSheet()
    toast.success(
      mode === 'create'
        ? t('measurementUnits.toast.createSuccess')
        : t('measurementUnits.toast.updateSuccess'),
    )
  }

  const handleDeleteSuccess = () => {
    closeDeleteDialog()
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-foreground">
        {t('measurementUnits.pageTitle')}
      </h1>

      <MeasurementUnitsList
        onCreateUnit={openCreateForm}
        onEditUnit={openEditForm}
        onDeleteUnit={openDeleteDialog}
      />

      {/* Measurement Unit Form Sheet (create/edit) */}
      <MeasurementUnitFormSheet
        open={formSheet.open}
        mode={formSheet.mode}
        unitId={formSheet.unitId}
        onClose={closeFormSheet}
        onSuccess={handleFormSuccess}
      />

      {/* Delete Confirmation Dialog */}
      <DeleteMeasurementUnitDialog
        open={deleteDialog.open}
        unit={deleteDialog.unit}
        onClose={closeDeleteDialog}
        onSuccess={handleDeleteSuccess}
      />
    </div>
  )
}
