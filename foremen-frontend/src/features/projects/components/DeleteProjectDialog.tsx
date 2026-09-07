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
import { ApiError } from '@/lib/api-client'
import { useDeleteProject } from '../api/mutation-hooks'
import type { ProjectDto } from '../types'

interface DeleteProjectDialogProps {
  open: boolean
  project: ProjectDto | null
  onClose: () => void
  onSuccess: () => void
}

/**
 * Confirm-and-delete dialog for a project (FOR-04-13 Req 8.9, 8.12). On confirm it calls
 * `DELETE /api/projects/{id}`; on success it shows a localized success toast, invalidates the list
 * (via {@link useDeleteProject}), and closes; on failure it shows a localized error toast (the
 * backend's verbatim message when available) and leaves the list state untouched.
 */
export function DeleteProjectDialog({
  open,
  project,
  onClose,
  onSuccess,
}: Readonly<DeleteProjectDialogProps>) {
  const { t } = useTranslation()
  const deleteMutation = useDeleteProject()

  const projectName = project?.name ?? ''

  function handleConfirm() {
    if (!project) return

    deleteMutation.mutate(project.id, {
      onSuccess: () => {
        toast.success(t('projects.toast.deleteSuccess'))
        onSuccess()
      },
      onError: (error) => {
        const message =
          error instanceof ApiError ? error.message : t('projects.toast.deleteError')
        toast.error(message)
      },
    })
  }

  return (
    <AlertDialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('projects.delete.title')}</AlertDialogTitle>
          <AlertDialogDescription>
            {t('projects.delete.description', { name: projectName })}
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
