import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'

import { MaterialCategoriesList } from './components/MaterialCategoriesList'
import { MaterialCategoryFormSheet } from './components/MaterialCategoryFormSheet'
import { DeleteMaterialCategoryDialog } from './components/DeleteMaterialCategoryDialog'
import type { MaterialCategoryDto, MaterialCategoryFormMode } from './types'

export interface FormSheetState {
  open: boolean
  mode: MaterialCategoryFormMode
  materialCategoryId: number | null
}

export interface DeleteDialogState {
  open: boolean
  materialCategory: MaterialCategoryDto | null
}

export default function MaterialCategoriesPage() {
  const { t } = useTranslation()

  // Form sheet state — controls create/edit material category sheet overlay
  const [formSheet, setFormSheet] = useState<FormSheetState>({
    open: false,
    mode: 'create',
    materialCategoryId: null,
  })

  // Delete confirmation dialog state
  const [deleteDialog, setDeleteDialog] = useState<DeleteDialogState>({
    open: false,
    materialCategory: null,
  })

  const openCreateForm = () => {
    setFormSheet({ open: true, mode: 'create', materialCategoryId: null })
  }

  const openEditForm = (materialCategoryId: number) => {
    setFormSheet({ open: true, mode: 'edit', materialCategoryId })
  }

  const closeFormSheet = () => {
    setFormSheet({ open: false, mode: 'create', materialCategoryId: null })
  }

  const openDeleteDialog = (materialCategory: MaterialCategoryDto) => {
    setDeleteDialog({ open: true, materialCategory })
  }

  const closeDeleteDialog = () => {
    setDeleteDialog({ open: false, materialCategory: null })
  }

  const handleFormSuccess = () => {
    const mode = formSheet.mode
    closeFormSheet()
    toast.success(
      mode === 'create'
        ? t('materialCategories.toast.createSuccess')
        : t('materialCategories.toast.updateSuccess'),
    )
  }

  const handleDeleteSuccess = () => {
    closeDeleteDialog()
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-foreground">
        {t('materialCategories.pageTitle')}
      </h1>

      <MaterialCategoriesList
        onCreateMaterialCategory={openCreateForm}
        onEditMaterialCategory={openEditForm}
        onDeleteMaterialCategory={openDeleteDialog}
      />

      {/* Material Category Form Sheet (create/edit) */}
      <MaterialCategoryFormSheet
        open={formSheet.open}
        mode={formSheet.mode}
        materialCategoryId={formSheet.materialCategoryId}
        onClose={closeFormSheet}
        onSuccess={handleFormSuccess}
      />

      {/* Delete Confirmation Dialog */}
      <DeleteMaterialCategoryDialog
        open={deleteDialog.open}
        materialCategory={deleteDialog.materialCategory}
        onClose={closeDeleteDialog}
        onSuccess={handleDeleteSuccess}
      />
    </div>
  )
}
