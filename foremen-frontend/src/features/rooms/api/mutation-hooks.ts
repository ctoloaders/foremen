import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createRoom, updateRoom, deleteRoom } from './rooms-api'
import { roomKeys } from './query-hooks'
import type { RoomCreateRequest, RoomUpdateRequest } from '../types'

export function useCreateRoom() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: RoomCreateRequest) => createRoom(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: roomKeys.lists() })
    },
  })
}

export function useUpdateRoom() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: RoomUpdateRequest }) =>
      updateRoom(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: roomKeys.lists() })
      queryClient.invalidateQueries({ queryKey: roomKeys.detail(variables.id) })
    },
  })
}

export function useDeleteRoom() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteRoom(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: roomKeys.lists() })
    },
  })
}
