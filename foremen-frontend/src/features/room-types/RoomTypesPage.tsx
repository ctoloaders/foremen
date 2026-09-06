import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'

import { RoomTypesList } from './components/RoomTypesList'
import { RoomTypeFormSheet } from './components/RoomTypeFormSheet'
import { DeleteRoomTypeDialog } from './components/DeleteRoomTypeDialog'
import type { RoomTypeDto, RoomTypeFormMode } from './types'

export interface FormSheetState {
  open: boolean
  mode: RoomTypeFormMode
  roomTypeId: number | null
}

export interface DeleteDialogState {
  open: boolean
  roomType: RoomTypeDto | null
}

export default function RoomTypesPage() {
  const { t } = useTranslation()

  // Form sheet state — controls create/edit room type sheet overlay
  const [formSheet, setFormSheet] = useState<FormSheetState>({
    open: false,
    mode: 'create',
    roomTypeId: null,
  })

  // Delete confirmation dialog state
  const [deleteDialog, setDeleteDialog] = useState<DeleteDialogState>({
    open: false,
    roomType: null,
  })

  const openCreateForm = () => {
    setFormSheet({ open: true, mode: 'create', roomTypeId: null })
  }

  const openEditForm = (roomTypeId: number) => {
    setFormSheet({ open: true, mode: 'edit', roomTypeId })
  }

  const closeFormSheet = () => {
    setFormSheet({ open: false, mode: 'create', roomTypeId: null })
  }

  const openDeleteDialog = (roomType: RoomTypeDto) => {
    setDeleteDialog({ open: true, roomType })
  }

  const closeDeleteDialog = () => {
    setDeleteDialog({ open: false, roomType: null })
  }

  const handleFormSuccess = () => {
    const mode = formSheet.mode
    closeFormSheet()
    toast.success(
      mode === 'create'
        ? t('roomTypes.toast.createSuccess')
        : t('roomTypes.toast.updateSuccess'),
    )
  }

  const handleDeleteSuccess = () => {
    closeDeleteDialog()
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-foreground">
        {t('roomTypes.pageTitle')}
      </h1>

      <RoomTypesList
        onCreateRoomType={openCreateForm}
        onEditRoomType={openEditForm}
        onDeleteRoomType={openDeleteDialog}
      />

      {/* Room Type Form Sheet (create/edit) */}
      <RoomTypeFormSheet
        open={formSheet.open}
        mode={formSheet.mode}
        roomTypeId={formSheet.roomTypeId}
        onClose={closeFormSheet}
        onSuccess={handleFormSuccess}
      />

      {/* Delete Confirmation Dialog */}
      <DeleteRoomTypeDialog
        open={deleteDialog.open}
        roomType={deleteDialog.roomType}
        onClose={closeDeleteDialog}
        onSuccess={handleDeleteSuccess}
      />
    </div>
  )
}
