/**
 * MeasurementUnitFormSheet — Sheet overlay for creating or editing a unit.
 *
 * Create mode: all fields empty, `code` editable, `active` defaults to true.
 * Edit mode: pre-populated from useMeasurementUnit(id); `code` shown read-only
 *            (immutable — the update body has no `code`).
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
import { useMeasurementUnit } from '../api/query-hooks'
import { useCreateMeasurementUnit, useUpdateMeasurementUnit } from '../api/mutation-hooks'
import {
  measurementUnitCreateSchema,
  measurementUnitUpdateSchema,
} from '../schemas/measurement-unit-schema'
import type {
  MeasurementUnitCreateFormValues,
  MeasurementUnitUpdateFormValues,
} from '../schemas/measurement-unit-schema'
import type { MeasurementUnitFormMode } from '../types'

interface MeasurementUnitFormSheetProps {
  open: boolean
  mode: MeasurementUnitFormMode
  unitId: number | null
  onClose: () => void
  onSuccess: () => void
}

export function MeasurementUnitFormSheet({
  open,
  mode,
  unitId,
  onClose,
  onSuccess,
}: MeasurementUnitFormSheetProps) {
  const { t } = useTranslation()

  // Fetch unit data in edit mode
  const { data: unitData, isLoading: isLoadingUnit } = useMeasurementUnit(
    mode === 'edit' ? unitId : null,
  )

  const createMutation = useCreateMeasurementUnit()
  const updateMutation = useUpdateMeasurementUnit()

  const isCreate = mode === 'create'
  const schema = isCreate ? measurementUnitCreateSchema : measurementUnitUpdateSchema
  const isPending = createMutation.isPending || updateMutation.isPending

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<MeasurementUnitCreateFormValues | MeasurementUnitUpdateFormValues>({
    resolver: zodResolver(schema),
    mode: 'onBlur',
    defaultValues: isCreate
      ? { code: '', nameRU: '', namePL: '', active: true }
      : { nameRU: '', namePL: '', active: true },
  })

  // Reset form when unit data is loaded (edit mode) or when mode changes.
  useEffect(() => {
    if (mode === 'edit' && unitData) {
      reset({
        nameRU: unitData.nameRU,
        namePL: unitData.namePL,
        active: unitData.active,
      })
    } else if (mode === 'create' && open) {
      reset({ code: '', nameRU: '', namePL: '', active: true })
    }
  }, [mode, unitData, open, reset])

  const onSubmit = (
    values: MeasurementUnitCreateFormValues | MeasurementUnitUpdateFormValues,
  ) => {
    if (isCreate) {
      const data = values as MeasurementUnitCreateFormValues
      createMutation.mutate(
        {
          code: data.code,
          nameRU: data.nameRU,
          namePL: data.namePL,
          active: data.active,
        },
        { onSuccess: () => onSuccess() },
      )
    } else {
      if (unitId == null) return
      const data = values as MeasurementUnitUpdateFormValues
      updateMutation.mutate(
        {
          id: unitId,
          data: {
            nameRU: data.nameRU,
            namePL: data.namePL,
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
              ? t('measurementUnits.form.titleCreate')
              : t('measurementUnits.form.titleEdit')}
          </SheetTitle>
          <SheetDescription>
            {isCreate
              ? t('measurementUnits.form.descriptionCreate')
              : t('measurementUnits.form.descriptionEdit')}
          </SheetDescription>
        </SheetHeader>

        {/* Loading placeholder in edit mode */}
        {mode === 'edit' && isLoadingUnit ? (
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
                  <label htmlFor="unit-code" className="text-sm font-medium text-foreground">
                    {t('measurementUnits.form.code')}
                  </label>
                  <input
                    id="unit-code"
                    type="text"
                    {...register(
                      'code' as keyof (
                        | MeasurementUnitCreateFormValues
                        | MeasurementUnitUpdateFormValues
                      ),
                    )}
                    placeholder="m2"
                    className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {'code' in errors && errors.code && (
                    <p className="text-xs text-destructive">{t(errors.code.message ?? '')}</p>
                  )}
                </div>
              )}

              {/* Edit mode: code shown read-only (immutable) */}
              {mode === 'edit' && unitData && (
                <div className="space-y-2">
                  <label className="text-sm font-medium text-foreground">
                    {t('measurementUnits.form.code')}
                  </label>
                  <input
                    type="text"
                    value={unitData.code}
                    disabled
                    className="h-10 w-full rounded-md border border-border bg-muted px-3 text-sm text-muted-foreground disabled:cursor-not-allowed"
                  />
                </div>
              )}

              {/* Name RU / Name PL — two columns on desktop */}
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div className="space-y-2">
                  <label htmlFor="unit-nameRU" className="text-sm font-medium text-foreground">
                    {t('measurementUnits.form.nameRU')}
                  </label>
                  <input
                    id="unit-nameRU"
                    type="text"
                    {...register('nameRU')}
                    className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {errors.nameRU && (
                    <p className="text-xs text-destructive">{t(errors.nameRU.message ?? '')}</p>
                  )}
                </div>

                <div className="space-y-2">
                  <label htmlFor="unit-namePL" className="text-sm font-medium text-foreground">
                    {t('measurementUnits.form.namePL')}
                  </label>
                  <input
                    id="unit-namePL"
                    type="text"
                    {...register('namePL')}
                    className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {errors.namePL && (
                    <p className="text-xs text-destructive">{t(errors.namePL.message ?? '')}</p>
                  )}
                </div>
              </div>

              {/* Active checkbox */}
              <div className="flex items-center gap-2">
                <input
                  id="unit-active"
                  type="checkbox"
                  {...register('active')}
                  className="h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-ring"
                />
                <label htmlFor="unit-active" className="text-sm font-medium text-foreground">
                  {t('measurementUnits.form.active')}
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
                    ? t('measurementUnits.form.submitCreate')
                    : t('measurementUnits.form.submitEdit')}
              </button>
            </SheetFooter>
          </form>
        )}
      </SheetContent>
    </Sheet>
  )
}
