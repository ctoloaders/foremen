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
import { useDeleteRoomType } from '../api/mutation-hooks'
import { ApiError } from '../api/room-types-api'
import type { RoomTypeDto } from '../types'

interface DeleteRoomTypeDialogProps {
  open: boolean
  roomType: RoomTypeDto | null
  onClose: () => void
  onSuccess: () => void
}

export function DeleteRoomTypeDialog({
  open,
  roomType,
  onClose,
  onSuccess,
}: DeleteRoomTypeDialogProps) {
  const { t } = useTranslation()
  const deleteMutation = useDeleteRoomType()

  const roomTypeName = roomType?.name ?? ''

  function handleConfirm() {
    if (!roomType) return

    deleteMutation.mutate(roomType.id, {
      onSuccess: () => {
        toast.success(t('roomTypes.toast.deleteSuccess'))
        onSuccess()
      },
      onError: (error) => {
        const message =
          error instanceof ApiError ? error.message : t('roomTypes.errors.network')
        toast.error(message)
      },
    })
  }

  return (
    <AlertDialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('roomTypes.delete.title')}</AlertDialogTitle>
          <AlertDialogDescription>
            {t('roomTypes.delete.description', { name: roomTypeName })}
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
