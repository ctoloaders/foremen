/**
 * WorkItemFormSheet — Sheet overlay for creating or editing a work item.
 *
 * Create mode: all fields empty, `active` defaults to true.
 * Edit mode: pre-populated from useWorkItem(id).
 *
 * Both modes expose the two FK selects (workCategoryId from /api/work-categories,
 * unitId from /api/measurement-units), plus nameRU, namePL, active. The option
 * lists use the referenced dictionaries' list endpoints, whose `name` field is
 * already locale-resolved by the backend.
 *
 * Validates on blur (individual fields) and on submit (entire form). Displays
 * inline error messages below invalid fields (localized via i18n).
 */
import { useEffect } from 'react'
import { Controller, useForm } from 'react-hook-form'
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
import { AsyncEntitySelect } from '@/components/ui/async-entity-select'
import { useWorkItem } from '../api/query-hooks'
import { useCreateWorkItem, useUpdateWorkItem } from '../api/mutation-hooks'
import { workItemCreateSchema, workItemUpdateSchema } from '../schemas/work-item-schema'
import type {
  WorkItemCreateFormValues,
  WorkItemUpdateFormValues,
} from '../schemas/work-item-schema'
import type { WorkItemFormMode } from '../types'

interface WorkItemFormSheetProps {
  open: boolean
  mode: WorkItemFormMode
  itemId: number | null
  onClose: () => void
  onSuccess: () => void
}

export function WorkItemFormSheet({
  open,
  mode,
  itemId,
  onClose,
  onSuccess,
}: WorkItemFormSheetProps) {
  const { t } = useTranslation()

  const { data: itemData, isLoading: isLoadingItem } = useWorkItem(
    mode === 'edit' ? itemId : null,
  )

  const createMutation = useCreateWorkItem()
  const updateMutation = useUpdateWorkItem()

  const isCreate = mode === 'create'
  const schema = isCreate ? workItemCreateSchema : workItemUpdateSchema
  const isPending = createMutation.isPending || updateMutation.isPending

  const {
    register,
    handleSubmit,
    reset,
    control,
    formState: { errors },
  } = useForm<WorkItemCreateFormValues | WorkItemUpdateFormValues>({
    resolver: zodResolver(schema),
    mode: 'onBlur',
    defaultValues: {
      workCategoryId: 0,
      unitId: 0,
      nameRU: '',
      namePL: '',
      active: true,
    },
  })

  // Reset form when item data is loaded (edit mode) or when mode changes.
  useEffect(() => {
    if (mode === 'edit' && itemData) {
      reset({
        workCategoryId: itemData.workCategoryId,
        unitId: itemData.unitId,
        nameRU: itemData.nameRU,
        namePL: itemData.namePL,
        active: itemData.active,
      })
    } else if (mode === 'create' && open) {
      reset({ workCategoryId: 0, unitId: 0, nameRU: '', namePL: '', active: true })
    }
  }, [mode, itemData, open, reset])

  const onSubmit = (values: WorkItemCreateFormValues | WorkItemUpdateFormValues) => {
    if (isCreate) {
      createMutation.mutate(
        {
          workCategoryId: values.workCategoryId,
          unitId: values.unitId,
          nameRU: values.nameRU,
          namePL: values.namePL,
          active: values.active,
        },
        { onSuccess: () => onSuccess() },
      )
    } else {
      if (itemId == null) return
      updateMutation.mutate(
        {
          id: itemId,
          data: {
            workCategoryId: values.workCategoryId,
            unitId: values.unitId,
            nameRU: values.nameRU,
            namePL: values.namePL,
            active: values.active,
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
            {isCreate ? t('workCatalog.form.titleCreate') : t('workCatalog.form.titleEdit')}
          </SheetTitle>
          <SheetDescription>
            {isCreate
              ? t('workCatalog.form.descriptionCreate')
              : t('workCatalog.form.descriptionEdit')}
          </SheetDescription>
        </SheetHeader>

        {mode === 'edit' && isLoadingItem ? (
          <div className="flex-1 space-y-4 py-4">
            <div className="h-10 w-full animate-pulse rounded-md bg-muted" />
            <div className="h-10 w-full animate-pulse rounded-md bg-muted" />
            <div className="h-10 w-full animate-pulse rounded-md bg-muted" />
          </div>
        ) : (
          <form onSubmit={handleSubmit(onSubmit)} className="flex flex-1 flex-col gap-5 py-4">
            <div className="flex-1 space-y-5">
              {/* Work category selector — async search + infinite-scroll combobox
                  (Bug 7). Options are sorted by `orderNo,asc` so the dropdown
                  follows the catalog's intended order rather than the entity
                  name (Bug 10). RHF value stays a number; `0` maps to the
                  select's "unselected" (null), preserving min(1) validation and
                  the submit payload. */}
              <div className="space-y-2">
                <label htmlFor="work-item-category" className="text-sm font-medium text-foreground">
                  {t('workCatalog.form.workCategory')}
                </label>
                <Controller
                  control={control}
                  name="workCategoryId"
                  render={({ field }) => (
                    <AsyncEntitySelect
                      id="work-item-category"
                      aria-label={t('workCatalog.form.workCategory')}
                      optionsPath="/api/work-categories"
                      sort="orderNo,asc"
                      disabled={isPending}
                      placeholder={t('workCatalog.form.selectWorkCategory')}
                      value={field.value ? Number(field.value) : null}
                      onChange={(nextId) => field.onChange(nextId ?? 0)}
                    />
                  )}
                />
                {errors.workCategoryId && (
                  <p className="text-xs text-destructive">
                    {t(errors.workCategoryId.message ?? '')}
                  </p>
                )}
              </div>

              {/* Unit selector — async combobox (Bug 7). */}
              <div className="space-y-2">
                <label htmlFor="work-item-unit" className="text-sm font-medium text-foreground">
                  {t('workCatalog.form.unit')}
                </label>
                <Controller
                  control={control}
                  name="unitId"
                  render={({ field }) => (
                    <AsyncEntitySelect
                      id="work-item-unit"
                      aria-label={t('workCatalog.form.unit')}
                      optionsPath="/api/measurement-units"
                      disabled={isPending}
                      placeholder={t('workCatalog.form.selectUnit')}
                      value={field.value ? Number(field.value) : null}
                      onChange={(nextId) => field.onChange(nextId ?? 0)}
                    />
                  )}
                />
                {errors.unitId && (
                  <p className="text-xs text-destructive">{t(errors.unitId.message ?? '')}</p>
                )}
              </div>

              {/* Name RU / Name PL — two columns on desktop */}
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div className="space-y-2">
                  <label htmlFor="work-item-nameRU" className="text-sm font-medium text-foreground">
                    {t('workCatalog.form.nameRU')}
                  </label>
                  <input
                    id="work-item-nameRU"
                    type="text"
                    {...register('nameRU')}
                    className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {errors.nameRU && (
                    <p className="text-xs text-destructive">{t(errors.nameRU.message ?? '')}</p>
                  )}
                </div>

                <div className="space-y-2">
                  <label htmlFor="work-item-namePL" className="text-sm font-medium text-foreground">
                    {t('workCatalog.form.namePL')}
                  </label>
                  <input
                    id="work-item-namePL"
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
                  id="work-item-active"
                  type="checkbox"
                  {...register('active')}
                  className="h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-ring"
                />
                <label htmlFor="work-item-active" className="text-sm font-medium text-foreground">
                  {t('workCatalog.form.active')}
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
                    ? t('workCatalog.form.submitCreate')
                    : t('workCatalog.form.submitEdit')}
              </button>
            </SheetFooter>
          </form>
        )}
      </SheetContent>
    </Sheet>
  )
}
