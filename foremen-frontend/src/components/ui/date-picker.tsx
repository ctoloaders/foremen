/**
 * DatePicker — a controlled, single-date picker built on the shared {@link Popover} +
 * {@link Calendar} (react-day-picker), mirroring the pattern already used by the data-table
 * {@link import('@/components/data-table/filters/DateFilter').DateFilter}.
 *
 * Wire format (FOR-04-bugs Bug 5 / Req 2.5): the picker reads and emits the date as the same
 * `'YYYY-MM-DD'` ISO string the project create/update payload and the zod schema already expect,
 * so replacing the previous native `<input type="date">` leaves the submitted wire format byte-for-
 * byte identical. `value` may be an empty string / null (no date); clearing emits `''`.
 *
 * The trigger shows a formatted, localized date (`dd.MM.yyyy`, matching the DateFilter trigger) and
 * a placeholder when empty. Selecting a day in the calendar emits the ISO date; a Clear action emits
 * the empty string.
 */
import { useTranslation } from 'react-i18next'
import { format, parseISO, isValid } from 'date-fns'
import { CalendarIcon } from 'lucide-react'

import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Calendar } from '@/components/ui/calendar'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'

interface DatePickerProps {
  /** ISO date string `'YYYY-MM-DD'`, or empty string / null when no date is selected. */
  value: string | null | undefined
  /** Emits the next ISO date `'YYYY-MM-DD'`, or `''` when the date is cleared. */
  onChange: (nextIsoDate: string) => void
  id?: string
  disabled?: boolean
  placeholder?: string
  'aria-label'?: string
}

/** Parse an ISO `'YYYY-MM-DD'` string into a Date, or undefined when empty/invalid. */
function parseIsoDate(value: string | null | undefined): Date | undefined {
  if (!value) return undefined
  const parsed = parseISO(value)
  return isValid(parsed) ? parsed : undefined
}

export function DatePicker({
  value,
  onChange,
  id,
  disabled,
  placeholder,
  'aria-label': ariaLabel,
}: Readonly<DatePickerProps>) {
  const { t } = useTranslation()
  const selected = parseIsoDate(value)

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          id={id}
          type="button"
          variant="outline"
          disabled={disabled}
          aria-label={ariaLabel}
          className={cn(
            'h-9 w-full justify-start text-left font-normal',
            !selected && 'text-muted-foreground',
          )}
        >
          <CalendarIcon className="mr-2 h-4 w-4" />
          {selected ? (
            format(selected, 'dd.MM.yyyy')
          ) : (
            <span className="text-muted-foreground">
              {placeholder ?? <>&mdash;</>}
            </span>
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-auto p-0" align="start">
        <Calendar
          mode="single"
          selected={selected}
          onSelect={(date) => {
            // Emit the ISO 'YYYY-MM-DD' string the payload/zod schema expect (unchanged wire format).
            onChange(date ? format(date, 'yyyy-MM-dd') : '')
          }}
        />
        {selected && (
          <div className="flex justify-end border-t border-border p-2">
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => onChange('')}
            >
              {t('common.clear')}
            </Button>
          </div>
        )}
      </PopoverContent>
    </Popover>
  )
}
