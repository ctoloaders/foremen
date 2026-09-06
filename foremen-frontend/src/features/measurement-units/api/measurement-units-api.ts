import { apiRequest } from '@/lib/api-client'
import { buildFetchQuery } from '@/components/data-table'
import type {
  PaginatedResponse,
  MeasurementUnitDto,
  MeasurementUnitExtendedDto,
  MeasurementUnitCreateRequest,
  MeasurementUnitUpdateRequest,
} from '../types'

const BASE_URL = '/api'

/**
 * Local error type kept for parity with the Roles API module. Network calls
 * below go through the shared {@link apiRequest} client, which throws its own
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

export function fetchMeasurementUnits(params: {
  page?: number
  size?: number
  query?: string
  sort?: string[]
}): Promise<PaginatedResponse<MeasurementUnitDto>> {
  // Serialize through the shared DataTable serializer so page/size/query/sort
  // are sent consistently with every other managed-entity table.
  const qs = buildFetchQuery({
    page: params.page ?? 0,
    size: params.size ?? 10,
    query: params.query,
    sort: params.sort ?? [],
  })
  return apiRequest<PaginatedResponse<MeasurementUnitDto>>(`${BASE_URL}/measurement-units?${qs}`)
}

export function fetchMeasurementUnit(id: number): Promise<MeasurementUnitExtendedDto> {
  return apiRequest<MeasurementUnitExtendedDto>(`${BASE_URL}/measurement-units/${id}`)
}

export function createMeasurementUnit(
  data: MeasurementUnitCreateRequest,
): Promise<MeasurementUnitExtendedDto> {
  return apiRequest<MeasurementUnitExtendedDto>(`${BASE_URL}/measurement-units`, {
    method: 'POST',
    body: data,
  })
}

export function updateMeasurementUnit(
  id: number,
  data: MeasurementUnitUpdateRequest,
): Promise<MeasurementUnitExtendedDto> {
  return apiRequest<MeasurementUnitExtendedDto>(`${BASE_URL}/measurement-units/${id}`, {
    method: 'PUT',
    body: data,
  })
}

export async function deleteMeasurementUnit(id: number): Promise<void> {
  await apiRequest<void>(`${BASE_URL}/measurement-units/${id}`, { method: 'DELETE' })
}
