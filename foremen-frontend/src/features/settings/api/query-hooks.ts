import { useQuery } from '@tanstack/react-query'
import { fetchDisplayPreferences } from './preferences-api'

export const preferencesKeys = {
  all: ['display-preferences'] as const,
  detail: (userId: number) => [...preferencesKeys.all, userId] as const,
}

/**
 * Fetches display preferences for a user.
 * Only enabled when userId is defined.
 * staleTime: 30 seconds.
 */
export function useDisplayPreferences(userId: number | undefined) {
  return useQuery({
    queryKey: preferencesKeys.detail(userId!),
    queryFn: () => fetchDisplayPreferences(userId!),
    enabled: userId != null,
    staleTime: 30_000,
    retry: 2,
  })
}
