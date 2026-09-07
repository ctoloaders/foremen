import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Loader2 } from 'lucide-react'

import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogHeader,
  AlertDialogFooter,
  AlertDialogTitle,
  AlertDialogDescription,
  AlertDialogCancel,
  AlertDialogAction,
} from '@/components/ui/alert-dialog'
import { useDeleteRoom } from '../api/mutation-hooks'
import { ApiError } from '../api/rooms-api'
import type { RoomDto } from '../types'

interface DeleteRoomDialogProps {
  open: boolean
  room: RoomDto | null
  onClose: () => void
  onSuccess: () => void
}

export function DeleteRoomDialog({
  open,
  room,
  onClose,
  onSuccess,
}: Readonly<DeleteRoomDialogProps>) {
  const { t } = useTranslation()
  const deleteMutation = useDeleteRoom()

  // Prefer the free-text label, fall back to the localized room-type name so the
  // confirmation always identifies the room being removed.
  const roomName = room?.label || room?.roomTypeName || ''

  function handleConfirm() {
    if (!room) return

    deleteMutation.mutate(room.id, {
      onSuccess: () => {
        toast.success(t('rooms.toast.deleteSuccess'))
        onSuccess()
      },
      onError: (error) => {
        const message =
          error instanceof ApiError ? error.message : t('rooms.errors.network')
        toast.error(message)
      },
    })
  }

  return (
    <AlertDialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('rooms.delete.title')}</AlertDialogTitle>
          <AlertDialogDescription>
            {t('rooms.delete.description', { name: roomName })}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel onClick={onClose} disabled={deleteMutation.isPending}>
            {t('common.cancel')}
          </AlertDialogCancel>
          <AlertDialogAction onClick={handleConfirm} disabled={deleteMutation.isPending}>
            {deleteMutation.isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
            {deleteMutation.isPending ? t('common.loading') : t('common.delete')}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
