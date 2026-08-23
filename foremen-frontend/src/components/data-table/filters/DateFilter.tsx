import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { format } from 'date-fns'
import { CalendarIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Calendar } from '@/components/ui/calendar'
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover'

interface DateFilterProps {
  field: string
  currentFrom?: string // ISO date
  currentTo?: string // ISO date
  onApply: (from?: string, to?: string) => void
  onClose: () => void
}

export function DateFilter({
  field: _field,
  currentFrom,
  currentTo,
  onApply,
  onClose,
}: DateFilterProps) {
  const { t } = useTranslation()
  const [from, setFrom] = useState<Date | undefined>(
    currentFrom ? new Date(currentFrom) : undefined,
  )
  const [to, setTo] = useState<Date | undefined>(
    currentTo ? new Date(currentTo) : undefined,
  )
  const [error, setError] = useState<string | null>(null)

  const handleApply = () => {
    if (from && to && to <= from) {
      setError(t('dataTable.filter.date.rangeError'))
      return
    }

    setError(null)
    const fromISO = from ? format(from, 'yyyy-MM-dd') : undefined
    const toISO = to ? format(to, 'yyyy-MM-dd') : undefined
    onApply(fromISO, toISO)
    onClose()
  }

  return (
    <div className="space-y-3">
      <div className="space-y-2">
        <label className="text-sm text-muted-foreground">
          {t('dataTable.filter.date.from')}
        </label>
        <Popover>
          <PopoverTrigger asChild>
            <Button
              variant="outline"
              className="w-full justify-start text-left font-normal"
            >
              <CalendarIcon className="mr-2 h-4 w-4" />
              {from ? (
                format(from, 'dd.MM.yyyy')
              ) : (
                <span className="text-muted-foreground">&mdash;</span>
              )}
            </Button>
          </PopoverTrigger>
          <PopoverContent className="w-auto p-0" align="start">
            <Calendar
              mode="single"
              selected={from}
              onSelect={(date) => {
                setFrom(date ?? undefined)
                setError(null)
              }}
            />
          </PopoverContent>
        </Popover>
      </div>
      <div className="space-y-2">
        <label className="text-sm text-muted-foreground">
          {t('dataTable.filter.date.to')}
        </label>
        <Popover>
          <PopoverTrigger asChild>
            <Button
              variant="outline"
              className="w-full justify-start text-left font-normal"
            >
              <CalendarIcon className="mr-2 h-4 w-4" />
              {to ? (
                format(to, 'dd.MM.yyyy')
              ) : (
                <span className="text-muted-foreground">&mdash;</span>
              )}
            </Button>
          </PopoverTrigger>
          <PopoverContent className="w-auto p-0" align="start">
            <Calendar
              mode="single"
              selected={to}
              onSelect={(date) => {
                setTo(date ?? undefined)
                setError(null)
              }}
            />
          </PopoverContent>
        </Popover>
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
