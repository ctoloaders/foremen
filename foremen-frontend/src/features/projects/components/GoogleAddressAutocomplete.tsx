/**
 * GoogleAddressAutocomplete — debounced address search backed by the reusable
 * backend Google Places proxy (`/api/addresses/*`, FOR-04-13 Req 8.4).
 *
 * Behaviour:
 *  - The user types a free-text address; input is debounced (300ms) before a
 *    `GET /api/addresses/autocomplete` call, so we don't fire a request per
 *    keystroke.
 *  - Predictions are shown in a dropdown. Selecting one calls
 *    `GET /api/addresses/details` and reports `{ googlePlaceId, formattedAddress,
 *    latitude, longitude }` to the parent via `onSelect`, which the project form
 *    stores in its own state.
 *  - The server-side Google key never reaches the browser; this component only
 *    ever sees normalized predictions/details.
 *
 * The component is controlled by `value` (the currently displayed address text)
 * so the owning form can seed it in edit mode and clear it on reset.
 */
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2, MapPin } from 'lucide-react'

import { cn } from '@/lib/utils'
import { useDebounce } from '@/hooks/useDebounce'
import { autocompleteAddress, fetchAddressDetails } from '../api/address-api'
import type { AddressPrediction, AddressSelection } from '../types'

export interface GoogleAddressAutocompleteProps {
  /** Currently displayed address text (controlled). */
  value: string
  /** Emit raw text edits (before a prediction is chosen). */
  onChange: (value: string) => void
  /**
   * Emit the resolved selection once a prediction's details are fetched. The
   * form stores `googlePlaceId`/`formattedAddress`/`latitude`/`longitude`.
   */
  onSelect: (selection: AddressSelection) => void
  disabled?: boolean
  error?: string
  id?: string
}

/** Minimum characters before firing an autocomplete request. */
const MIN_QUERY_LENGTH = 3

export function GoogleAddressAutocomplete({
  value,
  onChange,
  onSelect,
  disabled,
  error,
  id = 'project-address',
}: GoogleAddressAutocompleteProps) {
  const { t } = useTranslation()

  const [open, setOpen] = useState(false)
  const [predictions, setPredictions] = useState<AddressPrediction[]>([])
  const [isSearching, setIsSearching] = useState(false)
  const [isResolving, setIsResolving] = useState(false)
  const [loadError, setLoadError] = useState(false)

  const debouncedValue = useDebounce(value, 300)
  const containerRef = useRef<HTMLDivElement | null>(null)

  // Tracks the text of the last prediction the user selected, so we can skip
  // re-searching immediately after a selection sets `value` to that text.
  const justSelectedRef = useRef<string | null>(null)

  // --- Debounced autocomplete fetch ---
  useEffect(() => {
    const term = debouncedValue.trim()

    // Skip the search that would otherwise fire right after a selection sets
    // the input to the chosen description.
    if (justSelectedRef.current !== null && justSelectedRef.current === debouncedValue) {
      justSelectedRef.current = null
      return
    }

    if (term.length < MIN_QUERY_LENGTH) {
      setPredictions([])
      setIsSearching(false)
      setLoadError(false)
      return
    }

    let cancelled = false
    setIsSearching(true)
    setLoadError(false)

    autocompleteAddress(term)
      .then((results) => {
        if (cancelled) return
        setPredictions(results)
        setOpen(true)
      })
      .catch(() => {
        if (cancelled) return
        setPredictions([])
        setLoadError(true)
        setOpen(true)
      })
      .finally(() => {
        if (!cancelled) setIsSearching(false)
      })

    return () => {
      cancelled = true
    }
  }, [debouncedValue])

  // --- Close dropdown on outside click ---
  useEffect(() => {
    if (!open) return
    function onDocClick(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', onDocClick)
    return () => document.removeEventListener('mousedown', onDocClick)
  }, [open])

  async function handleSelect(prediction: AddressPrediction) {
    // Reflect the chosen description in the input and suppress the follow-up
    // search it would trigger.
    justSelectedRef.current = prediction.description
    onChange(prediction.description)
    setOpen(false)
    setPredictions([])

    setIsResolving(true)
    setLoadError(false)
    try {
      const details = await fetchAddressDetails(prediction.placeId)
      onSelect({
        googlePlaceId: prediction.placeId,
        formattedAddress: details.formattedAddress,
        latitude: details.latitude,
        longitude: details.longitude,
      })
    } catch {
      setLoadError(true)
    } finally {
      setIsResolving(false)
    }
  }

  return (
    <div ref={containerRef} className="relative">
      <div className="relative">
        <MapPin className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        <input
          id={id}
          type="text"
          value={value}
          disabled={disabled}
          autoComplete="off"
          role="combobox"
          aria-expanded={open}
          aria-controls={`${id}-listbox`}
          aria-autocomplete="list"
          placeholder={t('projects.form.addressPlaceholder')}
          onChange={(e) => onChange(e.target.value)}
          onFocus={() => {
            if (predictions.length > 0) setOpen(true)
          }}
          className={cn(
            'h-9 w-full rounded-md border bg-background pl-9 pr-9 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50',
            error ? 'border-destructive' : 'border-border',
          )}
        />
        {(isSearching || isResolving) && (
          <Loader2 className="absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 animate-spin text-muted-foreground" />
        )}
      </div>

      {error && <p className="mt-1 text-xs text-destructive">{error}</p>}

      {open && (
        <div className="absolute z-50 mt-1 w-full overflow-hidden rounded-md border border-border bg-popover shadow-md">
          <div id={`${id}-listbox`} role="listbox" className="max-h-60 overflow-y-auto p-1">
            {isSearching ? (
              <div className="flex items-center justify-center px-2 py-4">
                <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
                <span className="sr-only">{t('projects.form.addressSearching')}</span>
              </div>
            ) : loadError ? (
              <div className="px-2 py-4 text-center text-sm text-muted-foreground">
                {t('projects.form.addressError')}
              </div>
            ) : predictions.length === 0 ? (
              <div className="px-2 py-4 text-center text-sm text-muted-foreground">
                {t('projects.form.addressEmpty')}
              </div>
            ) : (
              predictions.map((prediction) => (
                <button
                  key={prediction.placeId}
                  type="button"
                  onClick={() => handleSelect(prediction)}
                  className="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm outline-none hover:bg-accent hover:text-accent-foreground focus:bg-accent focus:text-accent-foreground"
                >
                  <MapPin className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
                  <span className="truncate">{prediction.description}</span>
                </button>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  )
}
