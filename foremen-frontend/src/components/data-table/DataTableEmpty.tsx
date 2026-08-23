import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'

interface DataTableEmptyProps {
  type: 'empty' | 'error'
  onRetry?: () => void
  onClearFilters?: () => void
}

export function DataTableEmpty({ type, onRetry, onClearFilters }: DataTableEmptyProps) {
  const { t } = useTranslation()

  if (type === 'error') {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-center">
        <p className="text-sm text-muted-foreground mb-4">
          ⚠ {t('dataTable.error.message', 'Failed to load data.')}
        </p>
        {onRetry && (
          <Button variant="outline" size="sm" onClick={onRetry}>
            {t('dataTable.error.retry')}
          </Button>
        )}
      </div>
    )
  }

  return (
    <div className="flex flex-col items-center justify-center py-12 text-center">
      <p className="text-sm text-muted-foreground mb-4">
        {t('dataTable.empty.filtered')}
      </p>
      {onClearFilters && (
        <Button variant="outline" size="sm" onClick={onClearFilters}>
          {t('dataTable.filters.clearAll')}
        </Button>
      )}
    </div>
  )
}
