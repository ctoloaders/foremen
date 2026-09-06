import { apiRequest } from '@/lib/api-client'
import { buildFetchQuery } from '@/components/data-table'
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
  return apiRequest<PaginatedResponse<AuditRecord>>(
    `${BASE_URL}/audit?${buildFetchQuery(params)}`,
  )
}
