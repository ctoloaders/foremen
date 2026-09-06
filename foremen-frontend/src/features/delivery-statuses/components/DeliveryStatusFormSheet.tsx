/**
 * DeliveryStatusFormSheet — Sheet overlay for creating or editing a delivery status.
 *
 * Create mode: all fields empty, `code` editable, `orderNo` numeric input,
 *              `active` defaults to true.
 * Edit mode: pre-populated from useDeliveryStatus(id); `code` shown read-only
 *            (immutable — the update body has no `code`), but `orderNo`,
 *            `nameRU`, `namePL`, `active` remain editable.
 *
 * Validates on blur (individual fields) and on submit (entire form).
 * Displays inline error messages below invalid fields (localized via i18n).
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
import { useDeliveryStatus } from '../api/query-hooks'
import { useCreateDeliveryStatus, useUpdateDeliveryStatus } from '../api/mutation-hooks'
import {
  deliveryStatusCreateSchema,
  deliveryStatusUpdateSchema,
} from '../schemas/delivery-status-schema'
import type {
  DeliveryStatusCreateFormValues,
  DeliveryStatusUpdateFormValues,
} from '../schemas/delivery-status-schema'
import type { DeliveryStatusFormMode } from '../types'

interface DeliveryStatusFormSheetProps {
  open: boolean
  mode: DeliveryStatusFormMode
  statusId: number | null
  onClose: () => void
  onSuccess: () => void
}

export function DeliveryStatusFormSheet({
  open,
  mode,
  statusId,
  onClose,
  onSuccess,
}: DeliveryStatusFormSheetProps) {
  const { t } = useTranslation()

  // Fetch status data in edit mode
  const { data: statusData, isLoading: isLoadingStatus } = useDeliveryStatus(
    mode === 'edit' ? statusId : null,
  )

  const createMutation = useCreateDeliveryStatus()
  const updateMutation = useUpdateDeliveryStatus()

  const isCreate = mode === 'create'
  const schema = isCreate ? deliveryStatusCreateSchema : deliveryStatusUpdateSchema
  const isPending = createMutation.isPending || updateMutation.isPending

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<DeliveryStatusCreateFormValues | DeliveryStatusUpdateFormValues>({
    resolver: zodResolver(schema),
    mode: 'onBlur',
    defaultValues: isCreate
      ? { code: '', orderNo: 0, nameRU: '', namePL: '', active: true }
      : { orderNo: 0, nameRU: '', namePL: '', active: true },
  })

  // Reset form when status data is loaded (edit mode) or when mode changes.
  useEffect(() => {
    if (mode === 'edit' && statusData) {
      reset({
        orderNo: statusData.orderNo,
        nameRU: statusData.nameRU,
        namePL: statusData.namePL,
        active: statusData.active,
      })
    } else if (mode === 'create' && open) {
      reset({ code: '', orderNo: 0, nameRU: '', namePL: '', active: true })
    }
  }, [mode, statusData, open, reset])

  const onSubmit = (
    values: DeliveryStatusCreateFormValues | DeliveryStatusUpdateFormValues,
  ) => {
    if (isCreate) {
      const data = values as DeliveryStatusCreateFormValues
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
      if (statusId == null) return
      const data = values as DeliveryStatusUpdateFormValues
      updateMutation.mutate(
        {
          id: statusId,
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
              ? t('deliveryStatuses.form.titleCreate')
              : t('deliveryStatuses.form.titleEdit')}
          </SheetTitle>
          <SheetDescription>
            {isCreate
              ? t('deliveryStatuses.form.descriptionCreate')
              : t('deliveryStatuses.form.descriptionEdit')}
          </SheetDescription>
        </SheetHeader>

        {/* Loading placeholder in edit mode */}
        {mode === 'edit' && isLoadingStatus ? (
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
                  <label htmlFor="delivery-status-code" className="text-sm font-medium text-foreground">
                    {t('deliveryStatuses.form.code')}
                  </label>
                  <input
                    id="delivery-status-code"
                    type="text"
                    {...register(
                      'code' as keyof (
                        | DeliveryStatusCreateFormValues
                        | DeliveryStatusUpdateFormValues
                      ),
                    )}
                    placeholder="new"
                    className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {'code' in errors && errors.code && (
                    <p className="text-xs text-destructive">{t(errors.code.message ?? '')}</p>
                  )}
                </div>
              )}

              {/* Edit mode: code shown read-only (immutable) */}
              {mode === 'edit' && statusData && (
                <div className="space-y-2">
                  <label className="text-sm font-medium text-foreground">
                    {t('deliveryStatuses.form.code')}
                  </label>
                  <input
                    type="text"
                    value={statusData.code}
                    disabled
                    className="h-10 w-full rounded-md border border-border bg-muted px-3 text-sm text-muted-foreground disabled:cursor-not-allowed"
                  />
                </div>
              )}

              {/* Order number field — numeric, editable in both create and edit modes */}
              <div className="space-y-2">
                <label htmlFor="delivery-status-orderNo" className="text-sm font-medium text-foreground">
                  {t('deliveryStatuses.form.orderNo')}
                </label>
                <input
                  id="delivery-status-orderNo"
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
                  <label htmlFor="delivery-status-nameRU" className="text-sm font-medium text-foreground">
                    {t('deliveryStatuses.form.nameRU')}
                  </label>
                  <input
                    id="delivery-status-nameRU"
                    type="text"
                    {...register('nameRU')}
                    className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {errors.nameRU && (
                    <p className="text-xs text-destructive">{t(errors.nameRU.message ?? '')}</p>
                  )}
                </div>

                <div className="space-y-2">
                  <label htmlFor="delivery-status-namePL" className="text-sm font-medium text-foreground">
                    {t('deliveryStatuses.form.namePL')}
                  </label>
                  <input
                    id="delivery-status-namePL"
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
                  id="delivery-status-active"
                  type="checkbox"
                  {...register('active')}
                  className="h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-ring"
                />
                <label htmlFor="delivery-status-active" className="text-sm font-medium text-foreground">
                  {t('deliveryStatuses.form.active')}
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
                    ? t('deliveryStatuses.form.submitCreate')
                    : t('deliveryStatuses.form.submitEdit')}
              </button>
            </SheetFooter>
          </form>
        )}
      </SheetContent>
    </Sheet>
  )
}
