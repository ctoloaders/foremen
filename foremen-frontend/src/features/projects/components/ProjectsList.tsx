import { useCallback, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
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
import { ProjectStatusBadge } from './ProjectStatusBadge'
import { ProjectMembersCell } from './ProjectMembersCell'
import { ProjectClientCell } from './ProjectClientCell'
import { MEMBERS_USER_ID_PATH } from './ProjectMembersFilter'
import { CLIENT_ROLE_PREDICATE } from './ProjectClientFilter'
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
 *   collection / derived `client`. They are not sortable, but they ARE filterable
 *   as nested-entity reference column filters opened from the column filter icon
 *   (FOR-04-bugs Bug 11 / Change F7): each carries a {@link ColumnConfig.reference}
 *   descriptor sourcing user options from `/api/users`. The `members` column emits
 *   the plain nested fragment `members.user.id~in~<ids>` (single id → `==`); the
 *   `client` column emits the SAME id fragment AND-appended with the CLIENT-role
 *   predicate via `reference.extraPredicate`, reproducing exactly the compound
 *   `members.user.id~in~<ids> AND members.projectRole.code==CLIENT` semantics the
 *   old standalone `ProjectClientFilter` composed by hand.
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
    filterable: true,
    searchable: false,
    minWidth: '220px',
    // Nested-entity column filter (Bug 11): users multi-select emitting
    // `members.user.id~in~<ids>` — identical to the old ProjectMembersFilter.
    reference: {
      targetResource: 'users',
      optionsPath: '/api/users',
      labelField: 'name',
      labelI18n: false,
      idPath: MEMBERS_USER_ID_PATH,
    },
    render: (_value, row) => <ProjectMembersCell members={row.members} />,
  },
  {
    field: 'client',
    headerKey: 'projects.columns.client',
    dataType: 'string',
    sortable: false,
    filterable: true,
    searchable: false,
    minWidth: '160px',
    // Nested-entity column filter (Bug 11), CLIENT-scoped. Same users options
    // and `members.user.id` id path as the members column, but `extraPredicate`
    // AND-appends the CLIENT-role predicate so the emitted fragment is
    // `members.user.id~in~<ids> AND members.projectRole.code==CLIENT` — the
    // exact compound semantics the old ProjectClientFilter composed by hand.
    // The single-`==` / multi-`~in~` id collapse is preserved by the shared
    // emitReferenceFragment path, so the fragment is byte-for-byte identical.
    reference: {
      targetResource: 'users',
      optionsPath: '/api/users',
      labelField: 'name',
      labelI18n: false,
      idPath: MEMBERS_USER_ID_PATH,
      extraPredicate: CLIENT_ROLE_PREDICATE,
    },
    render: (_value, row) => <ProjectClientCell client={row.client} />,
  },
]

// Default sort: newest projects first (by startDate descending).
const defaultSort: SortState[] = [{ field: 'startDate', direction: 'desc', priority: 0 }]

/**
 * Server-side projects table (FOR-04-13 Req 8.1, 8.2, 8.3, 8.10).
 *
 * Renders `DataTable<ProjectDto>` over `fetchProjects`. The team **members** and
 * **client** filters are ordinary nested-entity column filters (Bug 11 / Change
 * F7): they live on the `members`/`client` columns as `ColumnConfig.reference`
 * descriptors and open from the column filter icon like every other nested
 * filter (e.g. WorkCatalogList). The DataTable owns their selected ids in its
 * own filter/URL/localStorage state and folds their fragments into the query it
 * passes to `fetchFn`, so no external fragment plumbing or manual query
 * invalidation is needed here.
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

  // Permission gating for the PROJECTS resource (FOR-03-07): MANAGER has
  // CREATE/READ/UPDATE but NOT DELETE, so delete hides for MANAGER; CLIENT/
  // FOREMAN/etc. get READ only, so create/edit/delete all hide.
  const canCreate = hasPermission('PROJECTS', 'CREATE')
  const canUpdate = hasPermission('PROJECTS', 'UPDATE')
  const canDelete = hasPermission('PROJECTS', 'DELETE')

  // Adapter: bridge DataTable's FetchParams to the projects API. The members
  // and client nested filters are now column filters folded into `params.query`
  // by the DataTable, so nothing extra is injected here.
  const fetchFn = useCallback(
    async (params: FetchParams): Promise<DTPaginatedResponse<ProjectDto>> => {
      const data = await fetchProjects({
        page: params.page,
        size: params.size,
        query: params.query || undefined,
        sort: params.sort,
      })

      return {
        ...data,
        first: data.number === 0,
        last: data.number >= data.totalPages - 1,
      }
    },
    [],
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
      {canCreate && (
        <div className="flex justify-end">
          <Button onClick={onCreateProject}>
            <Plus className="mr-2 h-4 w-4" />
            {t('projects.actions.create')}
          </Button>
        </div>
      )}

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
