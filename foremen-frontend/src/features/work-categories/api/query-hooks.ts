import { useQuery } from '@tanstack/react-query'
import { fetchWorkCategory } from './work-categories-api'

export const workCategoryKeys = {
  all: ['work-categories'] as const,
  lists: () => [...workCategoryKeys.all, 'list'] as const,
  list: (params: { page: number; size: number; query: string }) =>
    [...workCategoryKeys.lists(), params] as const,
  details: () => [...workCategoryKeys.all, 'detail'] as const,
  detail: (id: number) => [...workCategoryKeys.details(), id] as const,
}

export function useWorkCategory(id: number | null) {
  return useQuery({
    queryKey: workCategoryKeys.detail(id!),
    queryFn: () => fetchWorkCategory(id!),
    enabled: id != null,
    staleTime: 60_000,
    retry: 2,
  })
}
