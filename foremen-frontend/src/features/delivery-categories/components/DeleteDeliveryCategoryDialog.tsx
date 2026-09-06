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
import { useDeleteDeliveryCategory } from '../api/mutation-hooks'
import { ApiError } from '../api/delivery-categories-api'
import type { DeliveryCategoryDto } from '../types'

interface DeleteDeliveryCategoryDialogProps {
  open: boolean
  deliveryCategory: DeliveryCategoryDto | null
  onClose: () => void
  onSuccess: () => void
}

export function DeleteDeliveryCategoryDialog({
  open,
  deliveryCategory,
  onClose,
  onSuccess,
}: DeleteDeliveryCategoryDialogProps) {
  const { t } = useTranslation()
  const deleteMutation = useDeleteDeliveryCategory()

  const deliveryCategoryName = deliveryCategory?.name ?? ''

  function handleConfirm() {
    if (!deliveryCategory) return

    deleteMutation.mutate(deliveryCategory.id, {
      onSuccess: () => {
        toast.success(t('deliveryCategories.toast.deleteSuccess'))
        onSuccess()
      },
      onError: (error) => {
        const message =
          error instanceof ApiError ? error.message : t('deliveryCategories.errors.network')
        toast.error(message)
      },
    })
  }

  return (
    <AlertDialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('deliveryCategories.delete.title')}</AlertDialogTitle>
          <AlertDialogDescription>
            {t('deliveryCategories.delete.description', { name: deliveryCategoryName })}
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
