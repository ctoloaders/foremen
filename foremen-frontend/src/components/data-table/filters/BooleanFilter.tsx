import { useState } from 'react'
import { useTranslation } from 'react-i18next'

import { Button } from '@/components/ui/button'

interface BooleanFilterProps {
  field: string
  currentValue?: true | false | null
  onApply: (value: true | false | null) => void
  onClose: () => void
}

export function BooleanFilter({ field: _field, currentValue, onApply, onClose }: BooleanFilterProps) {
  const { t } = useTranslation()
  const [selected, setSelected] = useState<true | false | null | undefined>(currentValue)

  const handleApply = () => {
    if (selected !== undefined) {
      onApply(selected)
    }
    onClose()
  }

  return (
    <div className="space-y-3">
      <div className="space-y-2">
        <label className="flex items-center gap-2 cursor-pointer">
          <input
            type="radio"
            name="boolean-filter"
            checked={selected === true}
            onChange={() => setSelected(true)}
            className="h-4 w-4 accent-primary"
          />
          <span className="text-sm">{t('dataTable.filter.boolean.true', { defaultValue: 'Да' })}</span>
        </label>
        <label className="flex items-center gap-2 cursor-pointer">
          <input
            type="radio"
            name="boolean-filter"
            checked={selected === false}
            onChange={() => setSelected(false)}
            className="h-4 w-4 accent-primary"
          />
          <span className="text-sm">{t('dataTable.filter.boolean.false', { defaultValue: 'Нет' })}</span>
        </label>
        <label className="flex items-center gap-2 cursor-pointer">
          <input
            type="radio"
            name="boolean-filter"
            checked={selected === null}
            onChange={() => setSelected(null)}
            className="h-4 w-4 accent-primary"
          />
          <span className="text-sm text-muted-foreground">{t('dataTable.filter.boolean.null', { defaultValue: 'Не назначено' })}</span>
        </label>
      </div>
      <div className="flex justify-end">
        <Button size="sm" onClick={handleApply}>
          {t('dataTable.filter.apply', { defaultValue: 'Apply' })}
        </Button>
      </div>
    </div>
  )
}
