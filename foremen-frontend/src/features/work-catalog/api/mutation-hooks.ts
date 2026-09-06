import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createWorkItem, updateWorkItem, deleteWorkItem } from './work-catalog-api'
import { workItemKeys } from './query-hooks'
import type { WorkItemCreateRequest, WorkItemUpdateRequest } from '../types'

export function useCreateWorkItem() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: WorkItemCreateRequest) => createWorkItem(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: workItemKeys.lists() })
    },
  })
}

export function useUpdateWorkItem() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: WorkItemUpdateRequest }) =>
      updateWorkItem(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: workItemKeys.lists() })
      queryClient.invalidateQueries({ queryKey: workItemKeys.detail(variables.id) })
    },
  })
}

export function useDeleteWorkItem() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteWorkItem(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: workItemKeys.lists() })
    },
  })
}
