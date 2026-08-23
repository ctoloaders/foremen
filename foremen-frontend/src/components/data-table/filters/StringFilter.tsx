import { useState } from 'react'
import { useTranslation } from 'react-i18next'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

interface StringFilterProps {
  field: string
  currentValue?: string
  onApply: (value: string) => void
  onClose: () => void
}

export function StringFilter({
  field: _field,
  currentValue = '',
  onApply,
  onClose,
}: StringFilterProps) {
  const { t } = useTranslation()
  const [value, setValue] = useState(currentValue)

  const handleApply = () => {
    if (value.trim()) {
      onApply(value.trim())
    }
    onClose()
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleApply()
  }

  return (
    <div className="space-y-3">
      <Input
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder={t('dataTable.filter.string.placeholder')}
        autoFocus
      />
      <div className="flex justify-end">
        <Button size="sm" onClick={handleApply}>
          {t('dataTable.filter.apply', { defaultValue: 'Apply' })}
        </Button>
      </div>
    </div>
  )
}
