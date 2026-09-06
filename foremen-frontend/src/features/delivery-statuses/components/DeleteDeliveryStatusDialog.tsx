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
import { useDeleteDeliveryStatus } from '../api/mutation-hooks'
import { ApiError } from '../api/delivery-statuses-api'
import type { DeliveryStatusDto } from '../types'

interface DeleteDeliveryStatusDialogProps {
  open: boolean
  status: DeliveryStatusDto | null
  onClose: () => void
  onSuccess: () => void
}

export function DeleteDeliveryStatusDialog({
  open,
  status,
  onClose,
  onSuccess,
}: DeleteDeliveryStatusDialogProps) {
  const { t } = useTranslation()
  const deleteMutation = useDeleteDeliveryStatus()

  const statusName = status?.name ?? ''

  function handleConfirm() {
    if (!status) return

    deleteMutation.mutate(status.id, {
      onSuccess: () => {
        toast.success(t('deliveryStatuses.toast.deleteSuccess'))
        onSuccess()
      },
      onError: (error) => {
        const message =
          error instanceof ApiError ? error.message : t('deliveryStatuses.errors.network')
        toast.error(message)
      },
    })
  }

  return (
    <AlertDialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('deliveryStatuses.delete.title')}</AlertDialogTitle>
          <AlertDialogDescription>
            {t('deliveryStatuses.delete.description', { name: statusName })}
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
