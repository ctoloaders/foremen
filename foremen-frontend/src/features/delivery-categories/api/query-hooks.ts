import { useQuery } from '@tanstack/react-query'
import { fetchDeliveryCategory } from './delivery-categories-api'

export const deliveryCategoryKeys = {
  all: ['delivery-categories'] as const,
  lists: () => [...deliveryCategoryKeys.all, 'list'] as const,
  list: (params: { page: number; size: number; query: string }) =>
    [...deliveryCategoryKeys.lists(), params] as const,
  details: () => [...deliveryCategoryKeys.all, 'detail'] as const,
  detail: (id: number) => [...deliveryCategoryKeys.details(), id] as const,
}

export function useDeliveryCategory(id: number | null) {
  return useQuery({
    queryKey: deliveryCategoryKeys.detail(id!),
    queryFn: () => fetchDeliveryCategory(id!),
    enabled: id != null,
    staleTime: 60_000,
    retry: 2,
  })
}
