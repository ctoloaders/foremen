import { apiRequest } from '@/lib/api-client'
import { buildFetchQuery } from '@/components/data-table'
import type {
  PaginatedResponse,
  ProjectDto,
  ProjectReadDto,
  CreateProjectRequest,
  ProjectUpdateRequest,
} from '../types'

const BASE_URL = '/api'

/**
 * Fetches a paginated page of projects for the shared DataTable (FOR-04-13 Requirements 8.2, 8.11).
 * Serializes list params through the shared {@link buildFetchQuery} so `page`/`size`/`query`/`sort`
 * are sent identically to every other managed-entity table; filter fragments (e.g.
 * `members.user.id~in~...`) ride along inside `query`.
 */
export function fetchProjects(params: {
  page?: number
  size?: number
  query?: string
  sort?: string[]
}): Promise<PaginatedResponse<ProjectDto>> {
  const qs = buildFetchQuery({
    page: params.page ?? 0,
    size: params.size ?? 10,
    query: params.query,
    sort: params.sort ?? [],
  })
  return apiRequest<PaginatedResponse<ProjectDto>>(`${BASE_URL}/projects?${qs}`)
}

/** Fetches a single project by id (`GET /api/projects/{id}`); used to prefill the edit form. */
export function fetchProject(id: number): Promise<ProjectReadDto> {
  return apiRequest<ProjectReadDto>(`${BASE_URL}/projects/${id}`)
}

/**
 * Creates a project through the custom transactional endpoint `POST /api/projects` (Requirement
 * 2.1): base fields + team `members` + optional `client` block, all created in one transaction.
 * Returns the created project (with its generated id and persisted `status`); HTTP 201 on success.
 */
export function createProject(data: CreateProjectRequest): Promise<ProjectReadDto> {
  return apiRequest<ProjectReadDto>(`${BASE_URL}/projects`, {
    method: 'POST',
    body: data,
  })
}

/**
 * Updates a project's base fields via the generic update `PUT /api/projects/{id}` (Requirement 3.3;
 * team/client are not editable through the generic update).
 */
export function updateProject(id: number, data: ProjectUpdateRequest): Promise<ProjectReadDto> {
  return apiRequest<ProjectReadDto>(`${BASE_URL}/projects/${id}`, {
    method: 'PUT',
    body: data,
  })
}

/** Deletes a project by id (`DELETE /api/projects/{id}`). */
export async function deleteProject(id: number): Promise<void> {
  await apiRequest<void>(`${BASE_URL}/projects/${id}`, { method: 'DELETE' })
}
