import { AlertCircle } from 'lucide-react'
import { useTranslation } from 'react-i18next'

interface InlineErrorProps {
  message: string
  onRetry: () => void
}

/**
 * InlineError — Reusable inline error state component.
 *
 * Renders centered: error icon (AlertCircle), localized message, and "Retry" button.
 * Uses design tokens: muted-foreground for text, primary for button.
 *
 * Requirements: 11.1, 11.2, 11.3, 11.4, 11.5
 */
export function InlineError({ message, onRetry }: InlineErrorProps) {
  const { t } = useTranslation()

  return (
    <div className="flex flex-col items-center justify-center gap-4 py-12 text-center">
      <AlertCircle className="h-8 w-8 text-muted-foreground" />
      <p className="text-muted-foreground">{message}</p>
      <button
        type="button"
        onClick={onRetry}
        className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
      >
        {t('roles.actions.retry')}
      </button>
    </div>
  )
}
