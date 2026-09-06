/**
 * VatRateFormSheet — Sheet overlay for creating or editing a VAT rate.
 *
 * Create mode: all fields empty, `code` editable, `isDefault` defaults to false,
 *              `active` defaults to true.
 * Edit mode: pre-populated from useVatRate(id); `code` shown read-only
 *            (immutable — the update body has no `code`), but `rate`,
 *            `nameRU`, `namePL`, `isDefault`, `active` remain editable.
 *
 * Validates on blur (individual fields) and on submit (entire form).
 * Displays inline error messages below invalid fields (localized via i18n).
 *
 * Requirements: 6.3, 6.5
 */
import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useTranslation } from 'react-i18next'
import { Loader2 } from 'lucide-react'

import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
  SheetFooter,
} from '@/components/ui/sheet'
import { useVatRate } from '../api/query-hooks'
import { useCreateVatRate, useUpdateVatRate } from '../api/mutation-hooks'
import {
  vatRateCreateSchema,
  vatRateUpdateSchema,
} from '../schemas/vat-rate-schema'
import type {
  VatRateCreateFormValues,
  VatRateUpdateFormValues,
} from '../schemas/vat-rate-schema'
import type { VatRateFormMode } from '../types'

interface VatRateFormSheetProps {
  open: boolean
  mode: VatRateFormMode
  vatRateId: number | null
  onClose: () => void
  onSuccess: () => void
}

export function VatRateFormSheet({
  open,
  mode,
  vatRateId,
  onClose,
  onSuccess,
}: VatRateFormSheetProps) {
  const { t } = useTranslation()

  // Fetch VAT rate data in edit mode
  const { data: vatRateData, isLoading: isLoadingVatRate } = useVatRate(
    mode === 'edit' ? vatRateId : null,
  )

  const createMutation = useCreateVatRate()
  const updateMutation = useUpdateVatRate()

  const isCreate = mode === 'create'
  const schema = isCreate ? vatRateCreateSchema : vatRateUpdateSchema
  const isPending = createMutation.isPending || updateMutation.isPending

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<VatRateCreateFormValues | VatRateUpdateFormValues>({
    resolver: zodResolver(schema),
    mode: 'onBlur',
    defaultValues: isCreate
      ? { code: '', rate: 0, nameRU: '', namePL: '', isDefault: false, active: true }
      : { rate: 0, nameRU: '', namePL: '', isDefault: false, active: true },
  })

  // Reset form when VAT rate data is loaded (edit mode) or when mode changes.
  useEffect(() => {
    if (mode === 'edit' && vatRateData) {
      reset({
        rate: vatRateData.rate,
        nameRU: vatRateData.nameRU,
        namePL: vatRateData.namePL,
        isDefault: vatRateData.isDefault,
        active: vatRateData.active,
      })
    } else if (mode === 'create' && open) {
      reset({ code: '', rate: 0, nameRU: '', namePL: '', isDefault: false, active: true })
    }
  }, [mode, vatRateData, open, reset])

  const onSubmit = (
    values: VatRateCreateFormValues | VatRateUpdateFormValues,
  ) => {
    if (isCreate) {
      const data = values as VatRateCreateFormValues
      createMutation.mutate(
        {
          code: data.code,
          rate: data.rate,
          nameRU: data.nameRU,
          namePL: data.namePL,
          isDefault: data.isDefault,
          active: data.active,
        },
        { onSuccess: () => onSuccess() },
      )
    } else {
      if (vatRateId == null) return
      const data = values as VatRateUpdateFormValues
      updateMutation.mutate(
        {
          id: vatRateId,
          data: {
            rate: data.rate,
            nameRU: data.nameRU,
            namePL: data.namePL,
            isDefault: data.isDefault,
            active: data.active,
          },
        },
        { onSuccess: () => onSuccess() },
      )
    }
  }

  return (
    <Sheet open={open} onOpenChange={(isOpen) => { if (!isOpen) onClose() }}>
      <SheetContent side="right" className="flex w-full flex-col overflow-y-auto sm:max-w-lg">
        <SheetHeader>
          <SheetTitle>
            {isCreate
              ? t('vatRates.form.titleCreate')
              : t('vatRates.form.titleEdit')}
          </SheetTitle>
          <SheetDescription>
            {isCreate
              ? t('vatRates.form.descriptionCreate')
              : t('vatRates.form.descriptionEdit')}
          </SheetDescription>
        </SheetHeader>

        {/* Loading placeholder in edit mode */}
        {mode === 'edit' && isLoadingVatRate ? (
          <div className="flex-1 space-y-4 py-4">
            <div className="h-10 w-full animate-pulse rounded-md bg-muted" />
            <div className="h-10 w-full animate-pulse rounded-md bg-muted" />
            <div className="h-10 w-full animate-pulse rounded-md bg-muted" />
          </div>
        ) : (
          <form onSubmit={handleSubmit(onSubmit)} className="flex flex-1 flex-col gap-5 py-4">
            <div className="flex-1 space-y-5">
              {/* Code field — editable only in create mode */}
              {isCreate && (
                <div className="space-y-2">
                  <label htmlFor="vat-rate-code" className="text-sm font-medium text-foreground">
                    {t('vatRates.form.code')}
                  </label>
                  <input
                    id="vat-rate-code"
                    type="text"
                    {...register(
                      'code' as keyof (
                        | VatRateCreateFormValues
                        | VatRateUpdateFormValues
                      ),
                    )}
                    placeholder="23"
                    className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {'code' in errors && errors.code && (
                    <p className="text-xs text-destructive">{t(errors.code.message ?? '')}</p>
                  )}
                </div>
              )}

              {/* Edit mode: code shown read-only (immutable) */}
              {mode === 'edit' && vatRateData && (
                <div className="space-y-2">
                  <label className="text-sm font-medium text-foreground">
                    {t('vatRates.form.code')}
                  </label>
                  <input
                    type="text"
                    value={vatRateData.code}
                    disabled
                    className="h-10 w-full rounded-md border border-border bg-muted px-3 text-sm text-muted-foreground disabled:cursor-not-allowed"
                  />
                </div>
              )}

              {/* Rate field — numeric, editable in both create and edit modes */}
              <div className="space-y-2">
                <label htmlFor="vat-rate-rate" className="text-sm font-medium text-foreground">
                  {t('vatRates.form.rate')}
                </label>
                <input
                  id="vat-rate-rate"
                  type="number"
                  step="0.01"
                  min="0"
                  {...register('rate')}
                  placeholder="23.00"
                  className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                />
                {errors.rate && (
                  <p className="text-xs text-destructive">{t(errors.rate.message ?? '')}</p>
                )}
              </div>

              {/* Name RU / Name PL — two columns on desktop */}
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div className="space-y-2">
                  <label htmlFor="vat-rate-nameRU" className="text-sm font-medium text-foreground">
                    {t('vatRates.form.nameRU')}
                  </label>
                  <input
                    id="vat-rate-nameRU"
                    type="text"
                    {...register('nameRU')}
                    className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {errors.nameRU && (
                    <p className="text-xs text-destructive">{t(errors.nameRU.message ?? '')}</p>
                  )}
                </div>

                <div className="space-y-2">
                  <label htmlFor="vat-rate-namePL" className="text-sm font-medium text-foreground">
                    {t('vatRates.form.namePL')}
                  </label>
                  <input
                    id="vat-rate-namePL"
                    type="text"
                    {...register('namePL')}
                    className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {errors.namePL && (
                    <p className="text-xs text-destructive">{t(errors.namePL.message ?? '')}</p>
                  )}
                </div>
              </div>

              {/* isDefault checkbox */}
              <div className="flex items-center gap-2">
                <input
                  id="vat-rate-isDefault"
                  type="checkbox"
                  {...register('isDefault')}
                  className="h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-ring"
                />
                <label htmlFor="vat-rate-isDefault" className="text-sm font-medium text-foreground">
                  {t('vatRates.form.isDefault')}
                </label>
              </div>

              {/* Active checkbox */}
              <div className="flex items-center gap-2">
                <input
                  id="vat-rate-active"
                  type="checkbox"
                  {...register('active')}
                  className="h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-ring"
                />
                <label htmlFor="vat-rate-active" className="text-sm font-medium text-foreground">
                  {t('vatRates.form.active')}
                </label>
              </div>
            </div>

            {/* Footer: Cancel + Submit */}
            <SheetFooter className="border-t border-border pt-4">
              <button
                type="button"
                onClick={onClose}
                className="inline-flex h-9 items-center justify-center rounded-md border border-border bg-background px-4 text-sm font-medium text-foreground transition-colors hover:bg-muted"
              >
                {t('common.cancel')}
              </button>
              <button
                type="submit"
                disabled={isPending}
                className="inline-flex h-9 items-center justify-center gap-2 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:pointer-events-none disabled:opacity-50"
              >
                {isPending && <Loader2 className="h-4 w-4 animate-spin" />}
                {isPending
                  ? t('common.loading')
                  : isCreate
                    ? t('vatRates.form.submitCreate')
                    : t('vatRates.form.submitEdit')}
              </button>
            </SheetFooter>
          </form>
        )}
      </SheetContent>
    </Sheet>
  )
}
