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
import { useDeleteWorkCategory } from '../api/mutation-hooks'
import { ApiError } from '../api/work-categories-api'
import type { WorkCategoryDto } from '../types'

interface DeleteWorkCategoryDialogProps {
  open: boolean
  category: WorkCategoryDto | null
  onClose: () => void
  onSuccess: () => void
}

export function DeleteWorkCategoryDialog({
  open,
  category,
  onClose,
  onSuccess,
}: DeleteWorkCategoryDialogProps) {
  const { t } = useTranslation()
  const deleteMutation = useDeleteWorkCategory()

  const categoryName = category?.name ?? ''

  function handleConfirm() {
    if (!category) return

    deleteMutation.mutate(category.id, {
      onSuccess: () => {
        toast.success(t('workCategories.toast.deleteSuccess'))
        onSuccess()
      },
      onError: (error) => {
        const message =
          error instanceof ApiError ? error.message : t('workCategories.errors.network')
        toast.error(message)
      },
    })
  }

  return (
    <AlertDialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('workCategories.delete.title')}</AlertDialogTitle>
          <AlertDialogDescription>
            {t('workCategories.delete.description', { name: categoryName })}
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
