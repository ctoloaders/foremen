import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createRoomType,
  updateRoomType,
  deleteRoomType,
} from './room-types-api'
import { roomTypeKeys } from './query-hooks'
import type {
  RoomTypeCreateRequest,
  RoomTypeUpdateRequest,
} from '../types'

export function useCreateRoomType() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: RoomTypeCreateRequest) => createRoomType(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: roomTypeKeys.lists() })
    },
  })
}

export function useUpdateRoomType() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: RoomTypeUpdateRequest }) =>
      updateRoomType(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: roomTypeKeys.lists() })
      queryClient.invalidateQueries({ queryKey: roomTypeKeys.detail(variables.id) })
    },
  })
}

export function useDeleteRoomType() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteRoomType(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: roomTypeKeys.lists() })
    },
  })
}
