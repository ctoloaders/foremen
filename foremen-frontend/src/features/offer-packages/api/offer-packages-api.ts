import { apiRequest } from '@/lib/api-client'
import { buildFetchQuery } from '@/components/data-table'
import type {
  PaginatedResponse,
  OfferPackageDto,
  OfferPackageExtendedDto,
  OfferPackageCreateRequest,
  OfferPackageUpdateRequest,
} from '../types'

const BASE_URL = '/api'

/**
 * Local error type kept for parity with the other dictionary API modules. Network
 * calls below go through the shared {@link apiRequest} client, which throws its own
 * (structurally identical) `ApiError`; both carry `status` and `message`.
 */
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export function fetchOfferPackages(params: {
  page?: number
  size?: number
  query?: string
  sort?: string[]
}): Promise<PaginatedResponse<OfferPackageDto>> {
  // Serialize through the shared DataTable serializer so page/size/query/sort
  // are sent consistently with every other managed-entity table.
  const qs = buildFetchQuery({
    page: params.page ?? 0,
    size: params.size ?? 10,
    query: params.query,
    sort: params.sort ?? [],
  })
  return apiRequest<PaginatedResponse<OfferPackageDto>>(`${BASE_URL}/offer-packages?${qs}`)
}

export function fetchOfferPackage(id: number): Promise<OfferPackageExtendedDto> {
  return apiRequest<OfferPackageExtendedDto>(`${BASE_URL}/offer-packages/${id}`)
}

export function createOfferPackage(
  data: OfferPackageCreateRequest,
): Promise<OfferPackageExtendedDto> {
  return apiRequest<OfferPackageExtendedDto>(`${BASE_URL}/offer-packages`, {
    method: 'POST',
    body: data,
  })
}

export function updateOfferPackage(
  id: number,
  data: OfferPackageUpdateRequest,
): Promise<OfferPackageExtendedDto> {
  return apiRequest<OfferPackageExtendedDto>(`${BASE_URL}/offer-packages/${id}`, {
    method: 'PUT',
    body: data,
  })
}

export async function deleteOfferPackage(id: number): Promise<void> {
  await apiRequest<void>(`${BASE_URL}/offer-packages/${id}`, { method: 'DELETE' })
}
