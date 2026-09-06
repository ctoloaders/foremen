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
import { useDeleteWorkPrice } from '../api/mutation-hooks'
import { ApiError } from '../api/work-prices-api'
import type { WorkPriceDto } from '../types'

interface DeleteWorkPriceDialogProps {
  open: boolean
  price: WorkPriceDto | null
  onClose: () => void
  onSuccess: () => void
}

export function DeleteWorkPriceDialog({
  open,
  price,
  onClose,
  onSuccess,
}: DeleteWorkPriceDialogProps) {
  const { t } = useTranslation()
  const deleteMutation = useDeleteWorkPrice()

  const priceName = price?.workItemName ?? ''

  function handleConfirm() {
    if (!price) return

    deleteMutation.mutate(price.id, {
      onSuccess: () => {
        toast.success(t('workPrices.toast.deleteSuccess'))
        onSuccess()
      },
      onError: (error) => {
        const message =
          error instanceof ApiError ? error.message : t('workPrices.errors.network')
        toast.error(message)
      },
    })
  }

  return (
    <AlertDialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('workPrices.delete.title')}</AlertDialogTitle>
          <AlertDialogDescription>
            {t('workPrices.delete.description', { name: priceName })}
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
