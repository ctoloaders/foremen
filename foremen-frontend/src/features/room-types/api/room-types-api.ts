import { apiRequest } from '@/lib/api-client'
import { buildFetchQuery } from '@/components/data-table'
import type {
  PaginatedResponse,
  RoomTypeDto,
  RoomTypeExtendedDto,
  RoomTypeCreateRequest,
  RoomTypeUpdateRequest,
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

export function fetchRoomTypes(params: {
  page?: number
  size?: number
  query?: string
  sort?: string[]
}): Promise<PaginatedResponse<RoomTypeDto>> {
  // Serialize through the shared DataTable serializer so page/size/query/sort
  // are sent consistently with every other managed-entity table.
  const qs = buildFetchQuery({
    page: params.page ?? 0,
    size: params.size ?? 10,
    query: params.query,
    sort: params.sort ?? [],
  })
  return apiRequest<PaginatedResponse<RoomTypeDto>>(`${BASE_URL}/room-types?${qs}`)
}

export function fetchRoomType(id: number): Promise<RoomTypeExtendedDto> {
  return apiRequest<RoomTypeExtendedDto>(`${BASE_URL}/room-types/${id}`)
}

export function createRoomType(
  data: RoomTypeCreateRequest,
): Promise<RoomTypeExtendedDto> {
  return apiRequest<RoomTypeExtendedDto>(`${BASE_URL}/room-types`, {
    method: 'POST',
    body: data,
  })
}

export function updateRoomType(
  id: number,
  data: RoomTypeUpdateRequest,
): Promise<RoomTypeExtendedDto> {
  return apiRequest<RoomTypeExtendedDto>(`${BASE_URL}/room-types/${id}`, {
    method: 'PUT',
    body: data,
  })
}

export async function deleteRoomType(id: number): Promise<void> {
  await apiRequest<void>(`${BASE_URL}/room-types/${id}`, { method: 'DELETE' })
}
