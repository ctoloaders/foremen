import { useQuery } from '@tanstack/react-query'
import { fetchRoomType } from './room-types-api'

export const roomTypeKeys = {
  all: ['room-types'] as const,
  lists: () => [...roomTypeKeys.all, 'list'] as const,
  list: (params: { page: number; size: number; query: string }) =>
    [...roomTypeKeys.lists(), params] as const,
  details: () => [...roomTypeKeys.all, 'detail'] as const,
  detail: (id: number) => [...roomTypeKeys.details(), id] as const,
}

export function useRoomType(id: number | null) {
  return useQuery({
    queryKey: roomTypeKeys.detail(id!),
    queryFn: () => fetchRoomType(id!),
    enabled: id != null,
    staleTime: 60_000,
    retry: 2,
  })
}
