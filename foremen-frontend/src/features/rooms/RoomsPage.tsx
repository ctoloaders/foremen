import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'

import { RoomsList } from './components/RoomsList'
import { RoomFormSheet } from './components/RoomFormSheet'
import { DeleteRoomDialog } from './components/DeleteRoomDialog'
import type { RoomDto, RoomFormMode } from './types'

export interface FormSheetState {
  open: boolean
  mode: RoomFormMode
  roomId: number | null
}

export interface DeleteDialogState {
  open: boolean
  room: RoomDto | null
}

export default function RoomsPage() {
  const { t } = useTranslation()

  const [formSheet, setFormSheet] = useState<FormSheetState>({
    open: false,
    mode: 'create',
    roomId: null,
  })

  const [deleteDialog, setDeleteDialog] = useState<DeleteDialogState>({
    open: false,
    room: null,
  })

  const openCreateForm = () => {
    setFormSheet({ open: true, mode: 'create', roomId: null })
  }

  const openEditForm = (roomId: number) => {
    setFormSheet({ open: true, mode: 'edit', roomId })
  }

  const closeFormSheet = () => {
    setFormSheet({ open: false, mode: 'create', roomId: null })
  }

  const openDeleteDialog = (room: RoomDto) => {
    setDeleteDialog({ open: true, room })
  }

  const closeDeleteDialog = () => {
    setDeleteDialog({ open: false, room: null })
  }

  const handleFormSuccess = () => {
    const mode = formSheet.mode
    closeFormSheet()
    toast.success(
      mode === 'create'
        ? t('rooms.toast.createSuccess')
        : t('rooms.toast.updateSuccess'),
    )
  }

  const handleDeleteSuccess = () => {
    closeDeleteDialog()
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-foreground">{t('rooms.pageTitle')}</h1>

      <RoomsList
        onCreateRoom={openCreateForm}
        onEditRoom={openEditForm}
        onDeleteRoom={openDeleteDialog}
      />

      <RoomFormSheet
        open={formSheet.open}
        mode={formSheet.mode}
        roomId={formSheet.roomId}
        onClose={closeFormSheet}
        onSuccess={handleFormSuccess}
      />

      <DeleteRoomDialog
        open={deleteDialog.open}
        room={deleteDialog.room}
        onClose={closeDeleteDialog}
        onSuccess={handleDeleteSuccess}
      />
    </div>
  )
}
