import { useQuery } from '@tanstack/react-query'
import { fetchRoom } from './rooms-api'

export const roomKeys = {
  all: ['rooms'] as const,
  lists: () => [...roomKeys.all, 'list'] as const,
  list: (params: { page: number; size: number; query: string }) =>
    [...roomKeys.lists(), params] as const,
  details: () => [...roomKeys.all, 'detail'] as const,
  detail: (id: number) => [...roomKeys.details(), id] as const,
}

export function useRoom(id: number | null) {
  return useQuery({
    queryKey: roomKeys.detail(id!),
    queryFn: () => fetchRoom(id!),
    enabled: id != null,
    staleTime: 60_000,
    retry: 2,
  })
}
