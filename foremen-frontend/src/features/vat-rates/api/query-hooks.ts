import { useQuery } from '@tanstack/react-query'
import { fetchVatRate } from './vat-rates-api'

export const vatRateKeys = {
  all: ['vat-rates'] as const,
  lists: () => [...vatRateKeys.all, 'list'] as const,
  list: (params: { page: number; size: number; query: string }) =>
    [...vatRateKeys.lists(), params] as const,
  details: () => [...vatRateKeys.all, 'detail'] as const,
  detail: (id: number) => [...vatRateKeys.details(), id] as const,
}

export function useVatRate(id: number | null) {
  return useQuery({
    queryKey: vatRateKeys.detail(id!),
    queryFn: () => fetchVatRate(id!),
    enabled: id != null,
    staleTime: 60_000,
    retry: 2,
  })
}
