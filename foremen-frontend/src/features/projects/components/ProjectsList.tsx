import { useCallback, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { Plus, Pencil, Trash2 } from 'lucide-react'

import { DataTable } from '@/components/data-table'
import type {
  ColumnConfig,
  FetchParams,
  PaginatedResponse as DTPaginatedResponse,
  SortState,
} from '@/components/data-table'
import { Button } from '@/components/ui/button'
import { usePermission } from '@/hooks/usePermission'

import { fetchProjects } from '../api/projects-api'
import { projectKeys } from '../api/query-hooks'
import { ProjectStatusBadge } from './ProjectStatusBadge'
import { ProjectMembersCell } from './ProjectMembersCell'
import { ProjectClientCell } from './ProjectClientCell'
import { ProjectMembersFilter } from './ProjectMembersFilter'
import { ProjectClientFilter } from './ProjectClientFilter'
import type { ProjectDto, ProjectStatus } from '../types'

interface ProjectsListProps {
  onCreateProject: () => void
  onEditProject: (projectId: number) => void
  onDeleteProject: (project: ProjectDto) => void
}

/**
 * Columns for the projects list (FOR-04-13 Req 8.1, 8.3).
 *
 * - `name` / `address` (mapped to `formattedAddress` when present) / `area` /
 *   `startDate` / `endDate` are plain server-side sortable/filterable columns.
 * - `status` renders a localized {@link ProjectStatusBadge}.
 * - `members` and `client` are custom cells over the backend-projected `members`
 *   collection / derived `client`. They are not column-filterable/sortable — the
 *   member and client filters are dedicated multi-select controls above the table
 *   (they emit nested query fragments, see below).
 */
const columns: ColumnConfig<ProjectDto>[] = [
  {
    field: 'name',
    headerKey: 'projects.columns.name',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '200px',
  },
  {
    field: 'address',
    headerKey: 'projects.columns.address',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: true,
    minWidth: '220px',
    // Prefer the Google-resolved formatted address when present, else the raw
    // free-text address the user typed.
    render: (_value, row) => row.formattedAddress || row.address || '—',
  },
  {
    field: 'area',
    headerKey: 'projects.columns.area',
    dataType: 'number',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '120px',
    render: (value) => (value != null ? String(value) : '—'),
  },
  {
    field: 'startDate',
    headerKey: 'projects.columns.startDate',
    dataType: 'date',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '140px',
    render: (value) => (value ? String(value) : '—'),
  },
  {
    field: 'endDate',
    headerKey: 'projects.columns.endDate',
    dataType: 'date',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '140px',
    render: (value) => (value ? String(value) : '—'),
  },
  {
    field: 'status',
    headerKey: 'projects.columns.status',
    dataType: 'string',
    sortable: true,
    filterable: true,
    searchable: false,
    minWidth: '140px',
    render: (value) => <ProjectStatusBadge status={value as ProjectStatus} />,
  },
  {
    field: 'members',
    headerKey: 'projects.columns.members',
    dataType: 'string',
    sortable: false,
    filterable: false,
    searchable: false,
    minWidth: '220px',
    render: (_value, row) => <ProjectMembersCell members={row.members} />,
  },
  {
    field: 'client',
    headerKey: 'projects.columns.client',
    dataType: 'string',
    sortable: false,
    filterable: false,
    searchable: false,
    minWidth: '160px',
    render: (_value, row) => <ProjectClientCell client={row.client} />,
  },
]

// Default sort: newest projects first (by startDate descending).
const defaultSort: SortState[] = [{ field: 'startDate', direction: 'desc', priority: 0 }]

/**
 * Compose the DataTable-supplied base query fragment (search + column filters)
 * with the nested member/client filter fragments into a single ` AND `-joined
 * query string, matching the joiner {@link buildQueryString} uses internally so
 * the whole predicate is one consistent AND-chain on the wire.
 */
function composeQuery(
  base: string | undefined,
  membersFragment: string | null,
  clientFragment: string | null,
): string | undefined {
  const parts = [base, membersFragment, clientFragment].filter(
    (p): p is string => !!p && p.length > 0,
  )
  return parts.length > 0 ? parts.join(' AND ') : undefined
}

/**
 * Server-side projects table (FOR-04-13 Req 8.1, 8.2, 8.3, 8.10).
 *
 * Renders `DataTable<ProjectDto>` over `fetchProjects`, wiring in two dedicated
 * multi-select filter controls above the table: a general **members** filter and
 * an independent **client** filter. Each control emits a nested query fragment
 * (`members.user.id~in~<ids>` / the compound `... AND members.projectRole.code==CLIENT`);
 * the fragments are held here and injected into the DataTable `fetchFn` query.
 * Because the DataTable's internal query key does not observe these external
 * fragments, a fragment change invalidates the projects list query so the table
 * refetches with the new predicate.
 *
 * Create/edit/delete affordances are gated by `usePermission('PROJECTS', ...)`
 * (Req 8.10): the create button and each row action render only with the
 * matching grant.
 */
export function ProjectsList({
  onCreateProject,
  onEditProject,
  onDeleteProject,
}: Readonly<ProjectsListProps>) {
  const { t } = useTranslation()
  const { hasPermission } = usePermission()
  const queryClient = useQueryClient()

  // Permission gating for the PROJECTS resource (FOR-03-07): MANAGER has
  // CREATE/READ/UPDATE but NOT DELETE, so delete hides for MANAGER; CLIENT/
  // FOREMAN/etc. get READ only, so create/edit/delete all hide.
  const canCreate = hasPermission('PROJECTS', 'CREATE')
  const canUpdate = hasPermission('PROJECTS', 'UPDATE')
  const canDelete = hasPermission('PROJECTS', 'DELETE')

  // Selected ids + emitted fragments for the two independent nested filters.
  const [memberIds, setMemberIds] = useState<number[]>([])
  const [clientIds, setClientIds] = useState<number[]>([])
  const [membersFragment, setMembersFragment] = useState<string | null>(null)
  const [clientFragment, setClientFragment] = useState<string | null>(null)

  // Force the DataTable's list query to refetch when an external nested filter
  // fragment changes (its own query key does not observe these fragments).
  const refetchList = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: projectKeys.lists() })
  }, [queryClient])

  const handleMembersFragmentChange = useCallback(
    (fragment: string | null) => {
      setMembersFragment(fragment)
      refetchList()
    },
    [refetchList],
  )

  const handleClientFragmentChange = useCallback(
    (fragment: string | null) => {
      setClientFragment(fragment)
      refetchList()
    },
    [refetchList],
  )

  // Adapter: bridge DataTable's FetchParams to the projects API, injecting the
  // nested member/client fragments into the query and adding the first/last
  // flags the DataTable expects.
  const fetchFn = useCallback(
    async (params: FetchParams): Promise<DTPaginatedResponse<ProjectDto>> => {
      const query = composeQuery(params.query, membersFragment, clientFragment)
      const data = await fetchProjects({
        page: params.page,
        size: params.size,
        query,
        sort: params.sort,
      })

      return {
        ...data,
        first: data.number === 0,
        last: data.number >= data.totalPages - 1,
      }
    },
    [membersFragment, clientFragment],
  )

  const rowActions = useCallback(
    (project: ProjectDto) => {
      if (!canUpdate && !canDelete) return null
      return (
        <div className="flex items-center gap-1">
          {canUpdate && (
            <Button
              variant="ghost"
              size="icon"
              onClick={(e) => {
                e.stopPropagation()
                onEditProject(project.id)
              }}
              aria-label={t('common.edit')}
            >
              <Pencil className="h-4 w-4" />
            </Button>
          )}
          {canDelete && (
            <Button
              variant="ghost"
              size="icon"
              onClick={(e) => {
                e.stopPropagation()
                onDeleteProject(project)
              }}
              aria-label={t('common.delete')}
              className="text-destructive hover:text-destructive"
            >
              <Trash2 className="h-4 w-4" />
            </Button>
          )}
        </div>
      )
    },
    [onEditProject, onDeleteProject, t, canUpdate, canDelete],
  )

  const dataTableColumns = useMemo(() => columns, [])

  return (
    <div className="space-y-4">
      {/* Toolbar: nested filters + create button */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex flex-wrap gap-3">
          <div className="w-full min-w-[220px] rounded-md border border-border sm:w-auto">
            <ProjectMembersFilter
              value={memberIds}
              onChange={setMemberIds}
              onFragmentChange={handleMembersFragmentChange}
            />
          </div>
          <div className="w-full min-w-[220px] rounded-md border border-border sm:w-auto">
            <ProjectClientFilter
              value={clientIds}
              onChange={setClientIds}
              onFragmentChange={handleClientFragmentChange}
            />
          </div>
        </div>

        {canCreate && (
          <Button onClick={onCreateProject}>
            <Plus className="mr-2 h-4 w-4" />
            {t('projects.actions.create')}
          </Button>
        )}
      </div>

      <DataTable<ProjectDto>
        entityKey="projects"
        resource="PROJECTS"
        columns={dataTableColumns}
        fetchFn={fetchFn}
        defaultPageSize={10}
        pageSizeOptions={[10, 25, 50]}
        defaultSort={defaultSort}
        onRowClick={canUpdate ? (project) => onEditProject(project.id) : undefined}
        rowActions={rowActions}
      />
    </div>
  )
}
