import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { computeDiff, formatValue, isErrorSnapshot, getRowBackground } from '../utils/compute-diff'

interface ComparisonTableProps {
  snapshotBefore: Record<string, unknown> | string | null
  snapshotAfter: Record<string, unknown> | string | null
  operation: string
}

export function ComparisonTable({ snapshotBefore, snapshotAfter, operation }: ComparisonTableProps) {
  const { t } = useTranslation()
  const [showAll, setShowAll] = useState(false)

  // CREATE operation — green badge
  if (operation === 'CREATE') {
    return (
      <span className="inline-flex items-center px-2 py-0.5 rounded text-sm font-medium text-green-500 border border-green-500/30">
        {t('audit.comparison.badgeNew')}
      </span>
    )
  }

  // DELETE operation — red badge
  if (operation === 'DELETE') {
    return (
      <span className="inline-flex items-center px-2 py-0.5 rounded text-sm font-medium text-red-500 border border-red-500/30">
        {t('audit.comparison.badgeDeleted')}
      </span>
    )
  }

  // Error snapshot detection (applies to any operation)
  if (isErrorSnapshot(snapshotBefore) || isErrorSnapshot(snapshotAfter)) {
    const errorSnapshotRaw = isErrorSnapshot(snapshotAfter) ? snapshotAfter : snapshotBefore
    const errorSnapshot = typeof errorSnapshotRaw === 'string' ? JSON.parse(errorSnapshotRaw) : errorSnapshotRaw
    const errorMessage = errorSnapshot ? String(errorSnapshot['error'] ?? '') : ''
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-sm font-medium text-red-500 border border-red-500/30">
        {t('audit.comparison.badgeError')}
        {errorMessage && <span className="ml-1">{errorMessage}</span>}
      </span>
    )
  }

  // UPDATE (and other operations) — compute diff and render table
  const entries = computeDiff(snapshotBefore, snapshotAfter)
  const filteredEntries = showAll
    ? entries
    : entries.filter((e) => e.status !== 'unchanged')

  // All fields unchanged — show "no changes" message
  if (!showAll && filteredEntries.length === 0) {
    return (
      <div className="flex flex-col gap-2">
        <span className="text-muted-foreground text-sm">{t('audit.comparison.noChanges')}</span>
        <button
          type="button"
          className="text-xs text-muted-foreground underline cursor-pointer self-start"
          onClick={() => setShowAll(true)}
        >
          {t('audit.comparison.showAll')}
        </button>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-1">
      <button
        type="button"
        className="text-xs text-muted-foreground underline cursor-pointer self-start"
        onClick={() => setShowAll(!showAll)}
      >
        {showAll ? t('audit.comparison.showChanges') : t('audit.comparison.showAll')}
      </button>
      <table className="w-full text-sm border border-border rounded overflow-hidden">
        <thead>
          <tr className="bg-muted">
            <th className="px-2 py-1 text-left text-foreground font-medium border-r border-border">
              {t('audit.comparison.fieldHeader')}
            </th>
            <th className="px-2 py-1 text-left text-foreground font-medium border-r border-border">
              {t('audit.comparison.valueBefore')}
            </th>
            <th className="px-2 py-1 text-left text-foreground font-medium">
              {t('audit.comparison.valueAfter')}
            </th>
          </tr>
        </thead>
        <tbody>
          {filteredEntries.map((entry) => (
            <tr
              key={entry.field}
              style={{ backgroundColor: getRowBackground(entry.status) }}
            >
              <td className="px-2 py-1 text-foreground border-r border-border font-mono text-xs">
                {entry.field}
              </td>
              <td className="px-2 py-1 text-foreground border-r border-border font-mono text-xs break-all">
                {formatValue(entry.valueBefore)}
              </td>
              <td className="px-2 py-1 text-foreground font-mono text-xs break-all">
                {formatValue(entry.valueAfter)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
