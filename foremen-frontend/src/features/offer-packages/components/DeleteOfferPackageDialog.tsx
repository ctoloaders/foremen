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
import { useDeleteOfferPackage } from '../api/mutation-hooks'
import { ApiError } from '../api/offer-packages-api'
import type { OfferPackageDto } from '../types'

interface DeleteOfferPackageDialogProps {
  open: boolean
  offerPackage: OfferPackageDto | null
  onClose: () => void
  onSuccess: () => void
}

export function DeleteOfferPackageDialog({
  open,
  offerPackage,
  onClose,
  onSuccess,
}: DeleteOfferPackageDialogProps) {
  const { t } = useTranslation()
  const deleteMutation = useDeleteOfferPackage()

  const packageName = offerPackage?.name ?? ''

  function handleConfirm() {
    if (!offerPackage) return

    deleteMutation.mutate(offerPackage.id, {
      onSuccess: () => {
        toast.success(t('offerPackages.toast.deleteSuccess'))
        onSuccess()
      },
      onError: (error) => {
        const message =
          error instanceof ApiError ? error.message : t('offerPackages.errors.network')
        toast.error(message)
      },
    })
  }

  return (
    <AlertDialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('offerPackages.delete.title')}</AlertDialogTitle>
          <AlertDialogDescription>
            {t('offerPackages.delete.description', { name: packageName })}
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
