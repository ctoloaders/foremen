import { useMutation, useQueryClient } from '@tanstack/react-query'
import { patchDisplayPreferences } from './preferences-api'
import { preferencesKeys } from './query-hooks'
import type { DisplayPreferencesRequest } from '../types'

/**
 * Mutation hook for saving display preferences.
 * Invalidates the preferences query on success.
 */
export function useSavePreferences(userId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: DisplayPreferencesRequest) => patchDisplayPreferences(userId, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: preferencesKeys.detail(userId) })
    },
  })
}
