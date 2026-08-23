import type { FetchParams, PaginatedResponse } from '@/components/data-table/types'
import type { AuditRecord } from '../types'

const BASE_URL = '/api'

function getAcceptLanguage(): string {
  try {
    const locale = localStorage.getItem('foremen-locale')
    if (locale === 'ru') return 'ru'
  } catch {}
  return 'pl'
}

function getHeaders(): Record<string, string> {
  return {
    'Accept-Language': getAcceptLanguage(),
  }
}

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

  const response = await fetch(`${BASE_URL}/audit?${searchParams}`, { headers: getHeaders() })
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new Error(body.message || body.error || `HTTP ${response.status}`)
  }
  return response.json()
}
