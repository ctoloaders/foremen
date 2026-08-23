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
import { useDeleteRole } from '../api/mutation-hooks'
import { ApiError } from '../api/roles-api'
import type { RoleDto } from '../types'

interface DeleteRoleDialogProps {
  open: boolean
  role: RoleDto | null
  onClose: () => void
  onSuccess: () => void
}

export function DeleteRoleDialog({ open, role, onClose, onSuccess }: DeleteRoleDialogProps) {
  const { t } = useTranslation()
  const deleteMutation = useDeleteRole()

  const roleName = role?.name ?? ''

  function handleConfirm() {
    if (!role) return

    deleteMutation.mutate(role.id, {
      onSuccess: () => {
        toast.success(t('roles.toast.deleteSuccess'))
        onSuccess()
      },
      onError: (error) => {
        if (error instanceof ApiError && error.status === 403) {
          toast.error(t('roles.errors.systemDelete'))
        } else {
          const message = error instanceof ApiError ? error.message : t('roles.errors.network')
          toast.error(message)
        }
      },
    })
  }

  return (
    <AlertDialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('roles.delete.title')}</AlertDialogTitle>
          <AlertDialogDescription>
            {t('roles.delete.description', { name: roleName })}
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
