import { useQuery } from '@tanstack/react-query'
import { fetchCurrency } from './currencies-api'

export const currencyKeys = {
  all: ['currencies'] as const,
  lists: () => [...currencyKeys.all, 'list'] as const,
  list: (params: { page: number; size: number; query: string }) =>
    [...currencyKeys.lists(), params] as const,
  details: () => [...currencyKeys.all, 'detail'] as const,
  detail: (id: number) => [...currencyKeys.details(), id] as const,
}

export function useCurrency(id: number | null) {
  return useQuery({
    queryKey: currencyKeys.detail(id!),
    queryFn: () => fetchCurrency(id!),
    enabled: id != null,
    staleTime: 60_000,
    retry: 2,
  })
}
