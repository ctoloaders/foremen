import { useMutation, useQueryClient } from '@tanstack/react-query'
import { patchDisplayPreferences } from './preferences-api'
import { preferencesKeys } from './query-hooks'
import type { DisplayPreferencesRequest } from '../types'

export function useSavePreferences(userId: number | undefined) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: DisplayPreferencesRequest) => {
      if (userId == null) {
        return Promise.reject(new Error('No authenticated user id for saving preferences'))
      }
      return patchDisplayPreferences(userId, body)
    },
    onSuccess: () => {
      if (userId != null) {
        queryClient.invalidateQueries({ queryKey: preferencesKeys.detail(userId) })
      }
    },
  })
}
