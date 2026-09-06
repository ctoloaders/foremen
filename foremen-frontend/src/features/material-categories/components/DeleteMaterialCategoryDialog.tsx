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
import { useDeleteMaterialCategory } from '../api/mutation-hooks'
import { ApiError } from '../api/material-categories-api'
import type { MaterialCategoryDto } from '../types'

interface DeleteMaterialCategoryDialogProps {
  open: boolean
  materialCategory: MaterialCategoryDto | null
  onClose: () => void
  onSuccess: () => void
}

export function DeleteMaterialCategoryDialog({
  open,
  materialCategory,
  onClose,
  onSuccess,
}: DeleteMaterialCategoryDialogProps) {
  const { t } = useTranslation()
  const deleteMutation = useDeleteMaterialCategory()

  const materialCategoryName = materialCategory?.name ?? ''

  function handleConfirm() {
    if (!materialCategory) return

    deleteMutation.mutate(materialCategory.id, {
      onSuccess: () => {
        toast.success(t('materialCategories.toast.deleteSuccess'))
        onSuccess()
      },
      onError: (error) => {
        const message =
          error instanceof ApiError ? error.message : t('materialCategories.errors.network')
        toast.error(message)
      },
    })
  }

  return (
    <AlertDialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('materialCategories.delete.title')}</AlertDialogTitle>
          <AlertDialogDescription>
            {t('materialCategories.delete.description', { name: materialCategoryName })}
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
