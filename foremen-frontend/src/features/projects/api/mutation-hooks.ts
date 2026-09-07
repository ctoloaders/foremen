import { useMutation, useQueryClient } from '@tanstack/react-query'

import { createProject, updateProject, deleteProject } from './projects-api'
import { projectKeys } from './query-hooks'
import type { CreateProjectRequest, ProjectUpdateRequest } from '../types'

/**
 * Create a project through the custom transactional endpoint (`POST /api/projects`, Req 2.1).
 * Invalidates the projects list on success so the new project appears (Req 8.12).
 */
export function useCreateProject() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: CreateProjectRequest) => createProject(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: projectKeys.lists() })
    },
  })
}

/**
 * Update a project's base fields via the generic update (`PUT /api/projects/{id}`, Req 3.3).
 * Invalidates both the list and the edited project's detail on success (Req 8.12).
 */
export function useUpdateProject() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: ProjectUpdateRequest }) =>
      updateProject(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: projectKeys.lists() })
      queryClient.invalidateQueries({ queryKey: projectKeys.detail(variables.id) })
    },
  })
}

/** Delete a project (`DELETE /api/projects/{id}`); invalidates the list on success (Req 8.9, 8.12). */
export function useDeleteProject() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteProject(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: projectKeys.lists() })
    },
  })
}
