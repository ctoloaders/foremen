import PhoneInputBase from 'react-phone-number-input'
import type { Country } from 'react-phone-number-input'
import 'react-phone-number-input/style.css'
import { forwardRef } from 'react'
import { cn } from '@/lib/utils'

export interface PhoneInputProps {
  value: string | undefined
  onChange: (value: string | undefined) => void
  defaultCountry?: Country
  error?: string
  disabled?: boolean
}

/**
 * A styled wrapper around `react-phone-number-input` that matches
 * shadcn/ui dark-theme input design tokens.
 *
 * - Country selector displays flags and international calling codes
 * - Stores value in E.164 format
 * - Formats display according to the selected country's national pattern
 */
export const PhoneInput = forwardRef<HTMLDivElement, PhoneInputProps>(
  ({ value, onChange, defaultCountry = 'PL', error, disabled }, ref) => {
    return (
      <div ref={ref} className="phone-input-wrapper">
        <PhoneInputBase
          international
          defaultCountry={defaultCountry}
          value={value ?? ''}
          onChange={onChange}
          disabled={disabled}
          className={cn(
            'flex h-9 w-full rounded-md border bg-transparent px-3 py-1 text-sm shadow-sm transition-colors',
            'text-foreground placeholder:text-muted-foreground',
            'focus-within:outline-none focus-within:ring-1 focus-within:ring-ring',
            error ? 'border-destructive' : 'border-input',
            disabled && 'cursor-not-allowed opacity-50',
          )}
        />
        {error && (
          <p className="mt-1 text-xs text-destructive">{error}</p>
        )}
      </div>
    )
  },
)
PhoneInput.displayName = 'PhoneInput'
