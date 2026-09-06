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
import { useDeleteVatRate } from '../api/mutation-hooks'
import { ApiError } from '../api/vat-rates-api'
import type { VatRateDto } from '../types'

interface DeleteVatRateDialogProps {
  open: boolean
  vatRate: VatRateDto | null
  onClose: () => void
  onSuccess: () => void
}

export function DeleteVatRateDialog({
  open,
  vatRate,
  onClose,
  onSuccess,
}: DeleteVatRateDialogProps) {
  const { t } = useTranslation()
  const deleteMutation = useDeleteVatRate()

  const vatRateName = vatRate?.name ?? ''

  function handleConfirm() {
    if (!vatRate) return

    deleteMutation.mutate(vatRate.id, {
      onSuccess: () => {
        toast.success(t('vatRates.toast.deleteSuccess'))
        onSuccess()
      },
      onError: (error) => {
        const message =
          error instanceof ApiError ? error.message : t('vatRates.errors.network')
        toast.error(message)
      },
    })
  }

  return (
    <AlertDialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('vatRates.delete.title')}</AlertDialogTitle>
          <AlertDialogDescription>
            {t('vatRates.delete.description', { name: vatRateName })}
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
