import { useCallback } from 'react'
import { useTranslation } from 'react-i18next'

import {
  ReferenceFilter,
  type ReferenceFilterProps,
} from '@/components/data-table/ReferenceFilter'
import { emitReferenceFragment } from '@/components/data-table/utils/emitReferenceFragment'
import type { ReferenceInfo } from '@/components/data-table/types'

/**
 * Query-grammar id path for a project's team members. A `members` segment is a
 * `@OneToMany` collection on `ProjectEntity`; the backend `SpecificationBuilder`
 * transparently joins `project_members` (+ `users`) for this nested path and
 * sets `distinct(true)` to collapse the to-many duplicate rows (design.md,
 * FOR-04-01). So the emitted fragment filters the projects list down to those
 * having a matching member.
 */
export const MEMBERS_USER_ID_PATH = 'members.user.id'

/**
 * Reference descriptor wiring the members multi-select to the users options
 * endpoint. The backend i18n mapping resolves the user display name into the
 * option's `name` field, so `labelI18n` is false (the name is a proper name,
 * not a `nameRU`/`namePL` pair). `idPath` is the collection-qualified
 * `members.user.id` path the fragment emitter uses.
 */
const MEMBERS_REFERENCE: ReferenceInfo = {
  targetResource: 'users',
  optionsPath: '/api/users',
  labelField: 'name',
  labelI18n: false,
  idPath: MEMBERS_USER_ID_PATH,
}

/**
 * Emit the members filter fragment for a selection of user ids.
 *
 *   - single id    → `members.user.id==<id>`
 *   - multiple ids → `members.user.id~in~<id1,id2,...>`
 *   - empty set    → `null` (no fragment; the filter is inactive)
 *
 * Delegates to the shared {@link emitReferenceFragment}, so the operator
 * symbols stay aligned with the implemented backend query grammar
 * (tilde-wrapped `~in~`, `==`).
 *
 * @param userIds selected user ids (order preserved, not deduped)
 * @returns the query fragment, or `null` when nothing is selected
 */
export function emitMembersFragment(userIds: readonly number[]): string | null {
  return emitReferenceFragment(MEMBERS_USER_ID_PATH, userIds)
}

export interface ProjectMembersFilterProps {
  /** Selected user ids (controlled). */
  value?: number[]
  /** Emit the new selection of user ids. */
  onChange?: (userIds: number[]) => void
  /**
   * Emit the composed query fragment for the current selection
   * (`members.user.id~in~<ids>` / `members.user.id==<id>` / `null`). Fired
   * alongside {@link onChange} so an owner that composes filter fragments
   * directly (rather than holding ids) can consume the fragment without
   * re-deriving it.
   */
  onFragmentChange?: (fragment: string | null) => void
  /** Close the surrounding dropdown/popover, if any. */
  onClose?: ReferenceFilterProps['onClose']
  /** Bubble the target resource's 403 (no READ grant) up to the owner. */
  onForbiddenChange?: ReferenceFilterProps['onForbiddenChange']
}

/**
 * General project-team multi-select filter.
 *
 * A users multi-select (reusing the FOR-04-01 {@link ReferenceFilter} against
 * `/api/users`) that filters the projects list to the projects having at least
 * one of the selected users as a member. It emits:
 *
 *   - `members.user.id~in~<id1,id2,...>` for a multi-selection, and
 *   - `members.user.id==<id>` for a single selection (per the design's
 *     "single selection → `==`" collapse).
 *
 * This is the **general** members filter; the CLIENT-scoped variant lives in
 * `ProjectClientFilter` and is independent of this control.
 */
export function ProjectMembersFilter({
  value = [],
  onChange,
  onFragmentChange,
  onClose,
  onForbiddenChange,
}: Readonly<ProjectMembersFilterProps>) {
  const { t } = useTranslation()

  const handleChange = useCallback(
    (userIds: number[]) => {
      onChange?.(userIds)
      onFragmentChange?.(emitMembersFragment(userIds))
    },
    [onChange, onFragmentChange],
  )

  return (
    <div className="flex flex-col" data-testid="project-members-filter">
      <div className="border-b px-3 py-2 text-xs font-medium text-muted-foreground">
        {t('projects.filters.members')}
      </div>
      <ReferenceFilter
        reference={MEMBERS_REFERENCE}
        value={value}
        onChange={handleChange}
        onClose={onClose}
        onForbiddenChange={onForbiddenChange}
      />
    </div>
  )
}
