import { apiRequest } from '@/lib/api-client'
import type { FetchParams, PaginatedResponse } from '@/components/data-table/types'
import type { AuditRecord } from '../types'

const BASE_URL = '/api'

/**
 * Fetches a page of audit records. Goes through the shared {@link apiRequest}
 * client so the `Authorization: Bearer` header is attached and a `401` is
 * handled via refresh/retry + forced logout redirect.
 */
export async function fetchAuditRecords(
  params: FetchParams,
): Promise<PaginatedResponse<AuditRecord>> {
  const searchParams = new URLSearchParams()
  if (params.page != null) searchParams.set('page', String(params.page))
  if (params.size != null) searchParams.set('size', String(params.size))
  if (params.query) searchParams.set('query', params.query)
  for (const sortEntry of params.sort) {
    searchParams.append('sort', sortEntry)
  }

  return apiRequest<PaginatedResponse<AuditRecord>>(`${BASE_URL}/audit?${searchParams}`)
}
