import { useQuery } from '@tanstack/react-query'
import { fetchWorkItem } from './work-catalog-api'

export const workItemKeys = {
  all: ['work-items'] as const,
  lists: () => [...workItemKeys.all, 'list'] as const,
  list: (params: { page: number; size: number; query: string }) =>
    [...workItemKeys.lists(), params] as const,
  details: () => [...workItemKeys.all, 'detail'] as const,
  detail: (id: number) => [...workItemKeys.details(), id] as const,
}

export function useWorkItem(id: number | null) {
  return useQuery({
    queryKey: workItemKeys.detail(id!),
    queryFn: () => fetchWorkItem(id!),
    enabled: id != null,
    staleTime: 60_000,
    retry: 2,
  })
}
