import { useCallback } from 'react'
import { useTranslation } from 'react-i18next'

import {
  ReferenceFilter,
  type ReferenceFilterProps,
} from '@/components/data-table/ReferenceFilter'
import { emitReferenceFragment } from '@/components/data-table/utils/emitReferenceFragment'
import type { ReferenceInfo } from '@/components/data-table/types'
import { MEMBERS_USER_ID_PATH } from './ProjectMembersFilter'

/**
 * Query-grammar predicate pinning the member row to the CLIENT project role.
 * `members.projectRole.code` traverses the same `members` `@OneToMany` join
 * (reused by the `SpecificationBuilder` per attribute name) onto `roles.code`,
 * so combining it with `members.user.id~in~...` under a single ` AND ` requires
 * one member row that is BOTH the named user AND the CLIENT — exactly the
 * client-column semantics (design.md, Change 3).
 */
export const CLIENT_ROLE_PREDICATE = 'members.projectRole.code==CLIENT'

/**
 * Reference descriptor for the client multi-select. Same users options
 * endpoint as the general members filter, but its emitted fragment is
 * CLIENT-scoped (see {@link emitClientFragment}); the `idPath` is only used by
 * the underlying {@link ReferenceFilter} plumbing, not for the compound
 * fragment (which this component composes itself).
 */
const CLIENT_REFERENCE: ReferenceInfo = {
  targetResource: 'users',
  optionsPath: '/api/users',
  labelField: 'name',
  labelI18n: false,
  idPath: MEMBERS_USER_ID_PATH,
}

/**
 * Emit the compound client filter fragment for a selection of user ids.
 *
 *   - single id    → `members.user.id==<id> AND members.projectRole.code==CLIENT`
 *   - multiple ids → `members.user.id~in~<id1,id2,...> AND members.projectRole.code==CLIENT`
 *   - empty set    → `null` (no fragment; the filter is inactive)
 *
 * The user-id half reuses the shared {@link emitReferenceFragment} (so the
 * single→`==` / multi→`~in~` collapse matches the members filter and the
 * backend grammar), then the CLIENT-role predicate is AND-appended so the two
 * conditions must be satisfied by the same member row.
 *
 * @param userIds selected user ids (order preserved, not deduped)
 * @returns the compound query fragment, or `null` when nothing is selected
 */
export function emitClientFragment(userIds: readonly number[]): string | null {
  const userFragment = emitReferenceFragment(MEMBERS_USER_ID_PATH, userIds)
  if (userFragment === null) return null
  return `${userFragment} AND ${CLIENT_ROLE_PREDICATE}`
}

export interface ProjectClientFilterProps {
  /** Selected user ids (controlled). */
  value?: number[]
  /** Emit the new selection of user ids. */
  onChange?: (userIds: number[]) => void
  /**
   * Emit the composed compound query fragment for the current selection
   * (`members.user.id~in~<...> AND members.projectRole.code==CLIENT`, or `null`
   * when empty). Fired alongside {@link onChange}.
   */
  onFragmentChange?: (fragment: string | null) => void
  /** Close the surrounding dropdown/popover, if any. */
  onClose?: ReferenceFilterProps['onClose']
  /** Bubble the target resource's 403 (no READ grant) up to the owner. */
  onForbiddenChange?: ReferenceFilterProps['onForbiddenChange']
}

/**
 * CLIENT-column multi-select filter.
 *
 * An **independent** users multi-select (its own selection state, distinct from
 * the general members filter) that filters the projects list to the projects
 * whose CLIENT member is one of the selected users. It emits the compound
 * fragment:
 *
 *   `members.user.id~in~<id1,id2,...> AND members.projectRole.code==CLIENT`
 *
 * collapsing the user-id half to `members.user.id==<id>` for a single
 * selection. Because it composes its own fragment (not a plain reference
 * fragment), an owner should consume {@link onFragmentChange} rather than the
 * shared reference-fragment path.
 */
export function ProjectClientFilter({
  value = [],
  onChange,
  onFragmentChange,
  onClose,
  onForbiddenChange,
}: Readonly<ProjectClientFilterProps>) {
  const { t } = useTranslation()

  const handleChange = useCallback(
    (userIds: number[]) => {
      onChange?.(userIds)
      onFragmentChange?.(emitClientFragment(userIds))
    },
    [onChange, onFragmentChange],
  )

  return (
    <div className="flex flex-col" data-testid="project-client-filter">
      <div className="border-b px-3 py-2 text-xs font-medium text-muted-foreground">
        {t('projects.filters.client')}
      </div>
      <ReferenceFilter
        reference={CLIENT_REFERENCE}
        value={value}
        onChange={handleChange}
        onClose={onClose}
        onForbiddenChange={onForbiddenChange}
      />
    </div>
  )
}
