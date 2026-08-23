import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { useTranslation } from 'react-i18next'

interface JsonExpanderProps {
  data: Record<string, unknown> | null | undefined
}

export function JsonExpander({ data }: JsonExpanderProps) {
  const [expanded, setExpanded] = useState(false)
  const { t } = useTranslation()

  if (data === null || data === undefined) {
    return <span className="text-muted-foreground">—</span>
  }

  if (!expanded) {
    return (
      <Button variant="ghost" size="sm" onClick={() => setExpanded(true)}>
        {t('audit.snapshot.show')}
      </Button>
    )
  }

  return (
    <div>
      <Button variant="ghost" size="sm" onClick={() => setExpanded(false)}>
        {t('audit.snapshot.hide')}
      </Button>
      <pre className="bg-muted border border-border rounded-md p-2 font-mono text-xs overflow-x-auto max-w-[300px]">
        {JSON.stringify(data, null, 2)}
      </pre>
    </div>
  )
}
