import { useQuery } from '@tanstack/react-query'
import { fetchMaterialCategory } from './material-categories-api'

export const materialCategoryKeys = {
  all: ['material-categories'] as const,
  lists: () => [...materialCategoryKeys.all, 'list'] as const,
  list: (params: { page: number; size: number; query: string }) =>
    [...materialCategoryKeys.lists(), params] as const,
  details: () => [...materialCategoryKeys.all, 'detail'] as const,
  detail: (id: number) => [...materialCategoryKeys.details(), id] as const,
}

export function useMaterialCategory(id: number | null) {
  return useQuery({
    queryKey: materialCategoryKeys.detail(id!),
    queryFn: () => fetchMaterialCategory(id!),
    enabled: id != null,
    staleTime: 60_000,
    retry: 2,
  })
}
