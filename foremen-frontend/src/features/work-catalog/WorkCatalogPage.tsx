import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'

import { WorkCatalogList } from './components/WorkCatalogList'
import { WorkItemFormSheet } from './components/WorkItemFormSheet'
import { DeleteWorkItemDialog } from './components/DeleteWorkItemDialog'
import type { WorkItemDto, WorkItemFormMode } from './types'

export interface FormSheetState {
  open: boolean
  mode: WorkItemFormMode
  itemId: number | null
}

export interface DeleteDialogState {
  open: boolean
  item: WorkItemDto | null
}

export default function WorkCatalogPage() {
  const { t } = useTranslation()

  const [formSheet, setFormSheet] = useState<FormSheetState>({
    open: false,
    mode: 'create',
    itemId: null,
  })

  const [deleteDialog, setDeleteDialog] = useState<DeleteDialogState>({
    open: false,
    item: null,
  })

  const openCreateForm = () => {
    setFormSheet({ open: true, mode: 'create', itemId: null })
  }

  const openEditForm = (itemId: number) => {
    setFormSheet({ open: true, mode: 'edit', itemId })
  }

  const closeFormSheet = () => {
    setFormSheet({ open: false, mode: 'create', itemId: null })
  }

  const openDeleteDialog = (item: WorkItemDto) => {
    setDeleteDialog({ open: true, item })
  }

  const closeDeleteDialog = () => {
    setDeleteDialog({ open: false, item: null })
  }

  const handleFormSuccess = () => {
    const mode = formSheet.mode
    closeFormSheet()
    toast.success(
      mode === 'create'
        ? t('workCatalog.toast.createSuccess')
        : t('workCatalog.toast.updateSuccess'),
    )
  }

  const handleDeleteSuccess = () => {
    closeDeleteDialog()
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-foreground">
        {t('workCatalog.pageTitle')}
      </h1>

      <WorkCatalogList
        onCreateItem={openCreateForm}
        onEditItem={openEditForm}
        onDeleteItem={openDeleteDialog}
      />

      <WorkItemFormSheet
        open={formSheet.open}
        mode={formSheet.mode}
        itemId={formSheet.itemId}
        onClose={closeFormSheet}
        onSuccess={handleFormSuccess}
      />

      <DeleteWorkItemDialog
        open={deleteDialog.open}
        item={deleteDialog.item}
        onClose={closeDeleteDialog}
        onSuccess={handleDeleteSuccess}
      />
    </div>
  )
}
