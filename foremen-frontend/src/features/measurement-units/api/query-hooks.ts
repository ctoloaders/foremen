import { useQuery } from '@tanstack/react-query'
import { fetchMeasurementUnit } from './measurement-units-api'

export const measurementUnitKeys = {
  all: ['measurement-units'] as const,
  lists: () => [...measurementUnitKeys.all, 'list'] as const,
  list: (params: { page: number; size: number; query: string }) =>
    [...measurementUnitKeys.lists(), params] as const,
  details: () => [...measurementUnitKeys.all, 'detail'] as const,
  detail: (id: number) => [...measurementUnitKeys.details(), id] as const,
}

export function useMeasurementUnit(id: number | null) {
  return useQuery({
    queryKey: measurementUnitKeys.detail(id!),
    queryFn: () => fetchMeasurementUnit(id!),
    enabled: id != null,
    staleTime: 60_000,
    retry: 2,
  })
}
