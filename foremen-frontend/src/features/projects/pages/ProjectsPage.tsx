import { useState } from 'react'
import { useTranslation } from 'react-i18next'

import { ProjectsList } from '../components/ProjectsList'
import { ProjectFormSheet } from '../components/ProjectFormSheet'
import { DeleteProjectDialog } from '../components/DeleteProjectDialog'
import type { ProjectDto, ProjectFormMode } from '../types'

interface FormSheetState {
  open: boolean
  mode: ProjectFormMode
  projectId: number | null
}

interface DeleteDialogState {
  open: boolean
  project: ProjectDto | null
}

/**
 * Projects feature page (FOR-04-13 Req 8.1–8.14). Composes {@link ProjectsList}
 * with the create/edit {@link ProjectFormSheet} and {@link DeleteProjectDialog},
 * mirroring `WorkPricesPage`. The list gates create/edit/delete via
 * `usePermission('PROJECTS', ...)`; the form and delete dialog own their own
 * query invalidation and success/error toasts (Req 8.12), so this page only
 * orchestrates open/close state.
 *
 * Exported as the DEFAULT export at `@/features/projects/pages/ProjectsPage` so
 * the router's `React.lazy(() => import('@/features/projects/pages/ProjectsPage'))`
 * (task 11.7) resolves.
 */
export default function ProjectsPage() {
  const { t } = useTranslation()

  const [formSheet, setFormSheet] = useState<FormSheetState>({
    open: false,
    mode: 'create',
    projectId: null,
  })

  const [deleteDialog, setDeleteDialog] = useState<DeleteDialogState>({
    open: false,
    project: null,
  })

  const openCreateForm = () => {
    setFormSheet({ open: true, mode: 'create', projectId: null })
  }

  const openEditForm = (projectId: number) => {
    setFormSheet({ open: true, mode: 'edit', projectId })
  }

  const closeFormSheet = () => {
    setFormSheet({ open: false, mode: 'create', projectId: null })
  }

  const openDeleteDialog = (project: ProjectDto) => {
    setDeleteDialog({ open: true, project })
  }

  const closeDeleteDialog = () => {
    setDeleteDialog({ open: false, project: null })
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-foreground">
        {t('projects.pageTitle')}
      </h1>

      <ProjectsList
        onCreateProject={openCreateForm}
        onEditProject={openEditForm}
        onDeleteProject={openDeleteDialog}
      />

      <ProjectFormSheet
        open={formSheet.open}
        mode={formSheet.mode}
        projectId={formSheet.projectId}
        onClose={closeFormSheet}
        onSuccess={closeFormSheet}
      />

      <DeleteProjectDialog
        open={deleteDialog.open}
        project={deleteDialog.project}
        onClose={closeDeleteDialog}
        onSuccess={closeDeleteDialog}
      />
    </div>
  )
}
