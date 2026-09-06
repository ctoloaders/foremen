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
import { useDeleteMeasurementUnit } from '../api/mutation-hooks'
import { ApiError } from '../api/measurement-units-api'
import type { MeasurementUnitDto } from '../types'

interface DeleteMeasurementUnitDialogProps {
  open: boolean
  unit: MeasurementUnitDto | null
  onClose: () => void
  onSuccess: () => void
}

export function DeleteMeasurementUnitDialog({
  open,
  unit,
  onClose,
  onSuccess,
}: DeleteMeasurementUnitDialogProps) {
  const { t } = useTranslation()
  const deleteMutation = useDeleteMeasurementUnit()

  const unitName = unit?.name ?? ''

  function handleConfirm() {
    if (!unit) return

    deleteMutation.mutate(unit.id, {
      onSuccess: () => {
        toast.success(t('measurementUnits.toast.deleteSuccess'))
        onSuccess()
      },
      onError: (error) => {
        const message =
          error instanceof ApiError ? error.message : t('measurementUnits.errors.network')
        toast.error(message)
      },
    })
  }

  return (
    <AlertDialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('measurementUnits.delete.title')}</AlertDialogTitle>
          <AlertDialogDescription>
            {t('measurementUnits.delete.description', { name: unitName })}
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
