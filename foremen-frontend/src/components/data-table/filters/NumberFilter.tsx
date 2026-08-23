import { useState } from 'react'
import { useTranslation } from 'react-i18next'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

interface NumberFilterProps {
  field: string
  currentFrom?: number
  currentTo?: number
  onApply: (from?: number, to?: number) => void
  onClose: () => void
}

export function NumberFilter({
  field: _field,
  currentFrom,
  currentTo,
  onApply,
  onClose,
}: NumberFilterProps) {
  const { t } = useTranslation()
  const [from, setFrom] = useState<string>(
    currentFrom != null ? String(currentFrom) : '',
  )
  const [to, setTo] = useState<string>(
    currentTo != null ? String(currentTo) : '',
  )
  const [error, setError] = useState<string | null>(null)

  const handleApply = () => {
    const fromNum = from ? Number(from) : undefined
    const toNum = to ? Number(to) : undefined

    if (fromNum != null && toNum != null && toNum <= fromNum) {
      setError(t('dataTable.filter.number.rangeError'))
      return
    }

    setError(null)
    onApply(fromNum, toNum)
    onClose()
  }

  return (
    <div className="space-y-3">
      <div className="space-y-2">
        <label className="text-sm text-muted-foreground">
          {t('dataTable.filter.number.from')}
        </label>
        <Input
          type="number"
          value={from}
          onChange={(e) => {
            setFrom(e.target.value)
            setError(null)
          }}
          autoFocus
        />
      </div>
      <div className="space-y-2">
        <label className="text-sm text-muted-foreground">
          {t('dataTable.filter.number.to')}
        </label>
        <Input
          type="number"
          value={to}
          onChange={(e) => {
            setTo(e.target.value)
            setError(null)
          }}
        />
      </div>
      {error && <p className="text-sm text-destructive">{error}</p>}
      <div className="flex justify-end">
        <Button size="sm" onClick={handleApply}>
          {t('dataTable.filter.apply', { defaultValue: 'Apply' })}
        </Button>
      </div>
    </div>
  )
}
