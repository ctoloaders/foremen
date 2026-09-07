import { useQuery } from '@tanstack/react-query'
import { fetchProject } from './projects-api'

/**
 * React Query key factory for the projects feature (FOR-04-13). Mirrors the work-prices convention
 * so list invalidation (`projectKeys.lists()`) and per-record detail caching stay consistent across
 * the shared DataTable + form/delete flows.
 */
export const projectKeys = {
  all: ['projects'] as const,
  lists: () => [...projectKeys.all, 'list'] as const,
  list: (params: { page: number; size: number; query: string }) =>
    [...projectKeys.lists(), params] as const,
  details: () => [...projectKeys.all, 'detail'] as const,
  detail: (id: number) => [...projectKeys.details(), id] as const,
}

/** Fetch a single project by id to prefill the edit form (`GET /api/projects/{id}`). */
export function useProject(id: number | null) {
  return useQuery({
    queryKey: projectKeys.detail(id!),
    queryFn: () => fetchProject(id!),
    enabled: id != null,
    staleTime: 60_000,
    retry: 2,
  })
}
