import { apiRequest } from '@/lib/api-client'
import { buildFetchQuery } from '@/components/data-table'
import type {
  PaginatedResponse,
  RoomDto,
  RoomExtendedDto,
  RoomCreateRequest,
  RoomUpdateRequest,
} from '../types'

const BASE_URL = '/api'

/**
 * Local error type kept for parity with the other managed-entity API modules.
 * Network calls below go through the shared {@link apiRequest} client, which
 * throws its own (structurally identical) `ApiError` carrying `status`/`message`.
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

export function fetchRooms(params: {
  page?: number
  size?: number
  query?: string
  sort?: string[]
}): Promise<PaginatedResponse<RoomDto>> {
  // Serialize through the shared DataTable serializer so page/size/query/sort/filter
  // are sent consistently with every other managed-entity table.
  const qs = buildFetchQuery({
    page: params.page ?? 0,
    size: params.size ?? 10,
    query: params.query,
    sort: params.sort ?? [],
  })
  return apiRequest<PaginatedResponse<RoomDto>>(`${BASE_URL}/rooms?${qs}`)
}

export function fetchRoom(id: number): Promise<RoomExtendedDto> {
  return apiRequest<RoomExtendedDto>(`${BASE_URL}/rooms/${id}`)
}

export function createRoom(data: RoomCreateRequest): Promise<RoomExtendedDto> {
  return apiRequest<RoomExtendedDto>(`${BASE_URL}/rooms`, {
    method: 'POST',
    body: data,
  })
}

export function updateRoom(id: number, data: RoomUpdateRequest): Promise<RoomExtendedDto> {
  return apiRequest<RoomExtendedDto>(`${BASE_URL}/rooms/${id}`, {
    method: 'PUT',
    body: data,
  })
}

export async function deleteRoom(id: number): Promise<void> {
  await apiRequest<void>(`${BASE_URL}/rooms/${id}`, { method: 'DELETE' })
}
