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
import { useDeleteCurrency } from '../api/mutation-hooks'
import { ApiError } from '../api/currencies-api'
import type { CurrencyDto } from '../types'

interface DeleteCurrencyDialogProps {
  open: boolean
  currency: CurrencyDto | null
  onClose: () => void
  onSuccess: () => void
}

export function DeleteCurrencyDialog({
  open,
  currency,
  onClose,
  onSuccess,
}: DeleteCurrencyDialogProps) {
  const { t } = useTranslation()
  const deleteMutation = useDeleteCurrency()

  const currencyName = currency?.name ?? ''

  function handleConfirm() {
    if (!currency) return

    deleteMutation.mutate(currency.id, {
      onSuccess: () => {
        toast.success(t('currencies.toast.deleteSuccess'))
        onSuccess()
      },
      onError: (error) => {
        const message =
          error instanceof ApiError ? error.message : t('currencies.errors.network')
        toast.error(message)
      },
    })
  }

  return (
    <AlertDialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('currencies.delete.title')}</AlertDialogTitle>
          <AlertDialogDescription>
            {t('currencies.delete.description', { name: currencyName })}
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
