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
import { useDeleteWorkItem } from '../api/mutation-hooks'
import { ApiError } from '../api/work-catalog-api'
import type { WorkItemDto } from '../types'

interface DeleteWorkItemDialogProps {
  open: boolean
  item: WorkItemDto | null
  onClose: () => void
  onSuccess: () => void
}

export function DeleteWorkItemDialog({
  open,
  item,
  onClose,
  onSuccess,
}: DeleteWorkItemDialogProps) {
  const { t } = useTranslation()
  const deleteMutation = useDeleteWorkItem()

  const itemName = item?.name ?? ''

  function handleConfirm() {
    if (!item) return

    deleteMutation.mutate(item.id, {
      onSuccess: () => {
        toast.success(t('workCatalog.toast.deleteSuccess'))
        onSuccess()
      },
      onError: (error) => {
        const message =
          error instanceof ApiError ? error.message : t('workCatalog.errors.network')
        toast.error(message)
      },
    })
  }

  return (
    <AlertDialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('workCatalog.delete.title')}</AlertDialogTitle>
          <AlertDialogDescription>
            {t('workCatalog.delete.description', { name: itemName })}
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
