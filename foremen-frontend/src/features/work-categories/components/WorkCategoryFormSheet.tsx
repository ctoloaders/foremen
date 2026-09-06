/**
 * WorkCategoryFormSheet — Sheet overlay for creating or editing a work category.
 *
 * Create mode: all fields empty, `code` editable, `orderNo` numeric input,
 *              `active` defaults to true.
 * Edit mode: pre-populated from useWorkCategory(id); `code` shown read-only
 *            (immutable — the update body has no `code`), but `orderNo`,
 *            `nameRU`, `namePL`, `active` remain editable.
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
import { useWorkCategory } from '../api/query-hooks'
import { useCreateWorkCategory, useUpdateWorkCategory } from '../api/mutation-hooks'
import {
  workCategoryCreateSchema,
  workCategoryUpdateSchema,
} from '../schemas/work-category-schema'
import type {
  WorkCategoryCreateFormValues,
  WorkCategoryUpdateFormValues,
} from '../schemas/work-category-schema'
import type { WorkCategoryFormMode } from '../types'

interface WorkCategoryFormSheetProps {
  open: boolean
  mode: WorkCategoryFormMode
  categoryId: number | null
  onClose: () => void
  onSuccess: () => void
}

export function WorkCategoryFormSheet({
  open,
  mode,
  categoryId,
  onClose,
  onSuccess,
}: WorkCategoryFormSheetProps) {
  const { t } = useTranslation()

  // Fetch category data in edit mode
  const { data: categoryData, isLoading: isLoadingCategory } = useWorkCategory(
    mode === 'edit' ? categoryId : null,
  )

  const createMutation = useCreateWorkCategory()
  const updateMutation = useUpdateWorkCategory()

  const isCreate = mode === 'create'
  const schema = isCreate ? workCategoryCreateSchema : workCategoryUpdateSchema
  const isPending = createMutation.isPending || updateMutation.isPending

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<WorkCategoryCreateFormValues | WorkCategoryUpdateFormValues>({
    resolver: zodResolver(schema),
    mode: 'onBlur',
    defaultValues: isCreate
      ? { code: '', orderNo: 0, nameRU: '', namePL: '', active: true }
      : { orderNo: 0, nameRU: '', namePL: '', active: true },
  })

  // Reset form when category data is loaded (edit mode) or when mode changes.
  useEffect(() => {
    if (mode === 'edit' && categoryData) {
      reset({
        orderNo: categoryData.orderNo,
        nameRU: categoryData.nameRU,
        namePL: categoryData.namePL,
        active: categoryData.active,
      })
    } else if (mode === 'create' && open) {
      reset({ code: '', orderNo: 0, nameRU: '', namePL: '', active: true })
    }
  }, [mode, categoryData, open, reset])

  const onSubmit = (
    values: WorkCategoryCreateFormValues | WorkCategoryUpdateFormValues,
  ) => {
    if (isCreate) {
      const data = values as WorkCategoryCreateFormValues
      createMutation.mutate(
        {
          code: data.code,
          orderNo: data.orderNo,
          nameRU: data.nameRU,
          namePL: data.namePL,
          active: data.active,
        },
        { onSuccess: () => onSuccess() },
      )
    } else {
      if (categoryId == null) return
      const data = values as WorkCategoryUpdateFormValues
      updateMutation.mutate(
        {
          id: categoryId,
          data: {
            orderNo: data.orderNo,
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
              ? t('workCategories.form.titleCreate')
              : t('workCategories.form.titleEdit')}
          </SheetTitle>
          <SheetDescription>
            {isCreate
              ? t('workCategories.form.descriptionCreate')
              : t('workCategories.form.descriptionEdit')}
          </SheetDescription>
        </SheetHeader>

        {/* Loading placeholder in edit mode */}
        {mode === 'edit' && isLoadingCategory ? (
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
                  <label htmlFor="work-category-code" className="text-sm font-medium text-foreground">
                    {t('workCategories.form.code')}
                  </label>
                  <input
                    id="work-category-code"
                    type="text"
                    {...register(
                      'code' as keyof (
                        | WorkCategoryCreateFormValues
                        | WorkCategoryUpdateFormValues
                      ),
                    )}
                    placeholder="PRELIMINARY"
                    className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {'code' in errors && errors.code && (
                    <p className="text-xs text-destructive">{t(errors.code.message ?? '')}</p>
                  )}
                </div>
              )}

              {/* Edit mode: code shown read-only (immutable) */}
              {mode === 'edit' && categoryData && (
                <div className="space-y-2">
                  <label className="text-sm font-medium text-foreground">
                    {t('workCategories.form.code')}
                  </label>
                  <input
                    type="text"
                    value={categoryData.code}
                    disabled
                    className="h-10 w-full rounded-md border border-border bg-muted px-3 text-sm text-muted-foreground disabled:cursor-not-allowed"
                  />
                </div>
              )}

              {/* Order number field — numeric, editable in both create and edit modes */}
              <div className="space-y-2">
                <label htmlFor="work-category-orderNo" className="text-sm font-medium text-foreground">
                  {t('workCategories.form.orderNo')}
                </label>
                <input
                  id="work-category-orderNo"
                  type="number"
                  step="1"
                  min="0"
                  {...register('orderNo')}
                  placeholder="1"
                  className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                />
                {errors.orderNo && (
                  <p className="text-xs text-destructive">{t(errors.orderNo.message ?? '')}</p>
                )}
              </div>

              {/* Name RU / Name PL — two columns on desktop */}
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div className="space-y-2">
                  <label htmlFor="work-category-nameRU" className="text-sm font-medium text-foreground">
                    {t('workCategories.form.nameRU')}
                  </label>
                  <input
                    id="work-category-nameRU"
                    type="text"
                    {...register('nameRU')}
                    className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {errors.nameRU && (
                    <p className="text-xs text-destructive">{t(errors.nameRU.message ?? '')}</p>
                  )}
                </div>

                <div className="space-y-2">
                  <label htmlFor="work-category-namePL" className="text-sm font-medium text-foreground">
                    {t('workCategories.form.namePL')}
                  </label>
                  <input
                    id="work-category-namePL"
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
                  id="work-category-active"
                  type="checkbox"
                  {...register('active')}
                  className="h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-ring"
                />
                <label htmlFor="work-category-active" className="text-sm font-medium text-foreground">
                  {t('workCategories.form.active')}
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
                    ? t('workCategories.form.submitCreate')
                    : t('workCategories.form.submitEdit')}
              </button>
            </SheetFooter>
          </form>
        )}
      </SheetContent>
    </Sheet>
  )
}
