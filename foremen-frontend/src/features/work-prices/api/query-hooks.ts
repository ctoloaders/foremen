import { useQuery } from '@tanstack/react-query'
import { fetchWorkPrice } from './work-prices-api'

export const workPriceKeys = {
  all: ['work-prices'] as const,
  lists: () => [...workPriceKeys.all, 'list'] as const,
  list: (params: { page: number; size: number; query: string }) =>
    [...workPriceKeys.lists(), params] as const,
  details: () => [...workPriceKeys.all, 'detail'] as const,
  detail: (id: number) => [...workPriceKeys.details(), id] as const,
}

export function useWorkPrice(id: number | null) {
  return useQuery({
    queryKey: workPriceKeys.detail(id!),
    queryFn: () => fetchWorkPrice(id!),
    enabled: id != null,
    staleTime: 60_000,
    retry: 2,
  })
}
