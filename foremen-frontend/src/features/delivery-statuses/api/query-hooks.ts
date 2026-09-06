import { useQuery } from '@tanstack/react-query'
import { fetchDeliveryStatus } from './delivery-statuses-api'

export const deliveryStatusKeys = {
  all: ['delivery-statuses'] as const,
  lists: () => [...deliveryStatusKeys.all, 'list'] as const,
  list: (params: { page: number; size: number; query: string }) =>
    [...deliveryStatusKeys.lists(), params] as const,
  details: () => [...deliveryStatusKeys.all, 'detail'] as const,
  detail: (id: number) => [...deliveryStatusKeys.details(), id] as const,
}

export function useDeliveryStatus(id: number | null) {
  return useQuery({
    queryKey: deliveryStatusKeys.detail(id!),
    queryFn: () => fetchDeliveryStatus(id!),
    enabled: id != null,
    staleTime: 60_000,
    retry: 2,
  })
}
