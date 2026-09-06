import { useQuery } from '@tanstack/react-query'
import { fetchOfferPackage } from './offer-packages-api'

export const offerPackageKeys = {
  all: ['offer-packages'] as const,
  lists: () => [...offerPackageKeys.all, 'list'] as const,
  list: (params: { page: number; size: number; query: string }) =>
    [...offerPackageKeys.lists(), params] as const,
  details: () => [...offerPackageKeys.all, 'detail'] as const,
  detail: (id: number) => [...offerPackageKeys.details(), id] as const,
}

export function useOfferPackage(id: number | null) {
  return useQuery({
    queryKey: offerPackageKeys.detail(id!),
    queryFn: () => fetchOfferPackage(id!),
    enabled: id != null,
    staleTime: 60_000,
    retry: 2,
  })
}
