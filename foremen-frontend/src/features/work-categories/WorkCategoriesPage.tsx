import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'

import { WorkCategoriesList } from './components/WorkCategoriesList'
import { WorkCategoryFormSheet } from './components/WorkCategoryFormSheet'
import { DeleteWorkCategoryDialog } from './components/DeleteWorkCategoryDialog'
import type { WorkCategoryDto, WorkCategoryFormMode } from './types'

export interface FormSheetState {
  open: boolean
  mode: WorkCategoryFormMode
  categoryId: number | null
}

export interface DeleteDialogState {
  open: boolean
  category: WorkCategoryDto | null
}

export default function WorkCategoriesPage() {
  const { t } = useTranslation()

  // Form sheet state — controls create/edit category sheet overlay
  const [formSheet, setFormSheet] = useState<FormSheetState>({
    open: false,
    mode: 'create',
    categoryId: null,
  })

  // Delete confirmation dialog state
  const [deleteDialog, setDeleteDialog] = useState<DeleteDialogState>({
    open: false,
    category: null,
  })

  const openCreateForm = () => {
    setFormSheet({ open: true, mode: 'create', categoryId: null })
  }

  const openEditForm = (categoryId: number) => {
    setFormSheet({ open: true, mode: 'edit', categoryId })
  }

  const closeFormSheet = () => {
    setFormSheet({ open: false, mode: 'create', categoryId: null })
  }

  const openDeleteDialog = (category: WorkCategoryDto) => {
    setDeleteDialog({ open: true, category })
  }

  const closeDeleteDialog = () => {
    setDeleteDialog({ open: false, category: null })
  }

  const handleFormSuccess = () => {
    const mode = formSheet.mode
    closeFormSheet()
    toast.success(
      mode === 'create'
        ? t('workCategories.toast.createSuccess')
        : t('workCategories.toast.updateSuccess'),
    )
  }

  const handleDeleteSuccess = () => {
    closeDeleteDialog()
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-foreground">
        {t('workCategories.pageTitle')}
      </h1>

      <WorkCategoriesList
        onCreateCategory={openCreateForm}
        onEditCategory={openEditForm}
        onDeleteCategory={openDeleteDialog}
      />

      {/* Work Category Form Sheet (create/edit) */}
      <WorkCategoryFormSheet
        open={formSheet.open}
        mode={formSheet.mode}
        categoryId={formSheet.categoryId}
        onClose={closeFormSheet}
        onSuccess={handleFormSuccess}
      />

      {/* Delete Confirmation Dialog */}
      <DeleteWorkCategoryDialog
        open={deleteDialog.open}
        category={deleteDialog.category}
        onClose={closeDeleteDialog}
        onSuccess={handleDeleteSuccess}
      />
    </div>
  )
}
