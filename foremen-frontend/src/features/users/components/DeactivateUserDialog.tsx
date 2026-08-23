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
import { useDeactivateUser } from '../api/mutation-hooks'
import { ApiError } from '../api/users-api'
import type { UserDto } from '../types'

interface DeactivateUserDialogProps {
  open: boolean
  user: UserDto | null
  onClose: () => void
  onSuccess: () => void
}

export function DeactivateUserDialog({ open, user, onClose, onSuccess }: DeactivateUserDialogProps) {
  const { t } = useTranslation()
  const deactivateMutation = useDeactivateUser()

  const userName = user?.name ?? ''

  function handleConfirm() {
    if (!user) return

    deactivateMutation.mutate(user.id, {
      onSuccess: () => {
        onSuccess()
      },
      onError: (error) => {
        const message = error instanceof ApiError ? error.message : t('users.errors.network')
        toast.error(message)
      },
    })
  }

  return (
    <AlertDialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('users.dialog.deactivateTitle')}</AlertDialogTitle>
          <AlertDialogDescription>
            {t('users.dialog.deactivateDescription', { name: userName })}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel onClick={onClose} disabled={deactivateMutation.isPending}>
            {t('common.cancel')}
          </AlertDialogCancel>
          <AlertDialogAction onClick={handleConfirm} disabled={deactivateMutation.isPending}>
            {deactivateMutation.isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
            {deactivateMutation.isPending
              ? t('common.loading')
              : t('users.dialog.deactivateConfirm')}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
