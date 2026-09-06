/**
 * WorkPriceFormSheet — Sheet overlay for creating or editing a work price.
 *
 * Create mode: all fields empty.
 * Edit mode: pre-populated from useWorkPrice(id).
 *
 * Both modes expose the two FK selects (workItemId from /api/work-items, currencyId
 * from /api/currencies), plus netPrice, validFrom, and optional validTo. The option
 * lists use the referenced endpoints' list endpoints; work items expose a
 * locale-resolved `name`, currencies expose a `code`.
 *
 * Validates on blur (individual fields) and on submit (entire form). Displays
 * inline error messages below invalid fields (localized via i18n).
 */
import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { Loader2 } from 'lucide-react'

import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
  SheetFooter,
} from '@/components/ui/sheet'
import { apiRequest } from '@/lib/api-client'
import { useWorkPrice } from '../api/query-hooks'
import { useCreateWorkPrice, useUpdateWorkPrice } from '../api/mutation-hooks'
import { workPriceCreateSchema, workPriceUpdateSchema } from '../schemas/work-price-schema'
import type {
  WorkPriceCreateFormValues,
  WorkPriceUpdateFormValues,
} from '../schemas/work-price-schema'
import type { PaginatedResponse, WorkPriceFormMode } from '../types'

interface WorkPriceFormSheetProps {
  open: boolean
  mode: WorkPriceFormMode
  priceId: number | null
  onClose: () => void
  onSuccess: () => void
}

/** Minimal option row from a reference list endpoint. */
interface ReferenceOption {
  id: number
  name?: string
  code?: string
}

function useReferenceOptions(path: string, enabled: boolean) {
  return useQuery({
    queryKey: ['work-prices-options', path],
    queryFn: () =>
      apiRequest<PaginatedResponse<ReferenceOption>>(
        `${path}?page=0&size=200&sort=name,asc`,
      ),
    enabled,
    staleTime: 60_000,
  })
}

function useCurrencyOptions(enabled: boolean) {
  return useQuery({
    queryKey: ['work-prices-options', '/api/currencies'],
    queryFn: () =>
      apiRequest<PaginatedResponse<ReferenceOption>>(
        `/api/currencies?page=0&size=200&sort=code,asc`,
      ),
    enabled,
    staleTime: 60_000,
  })
}

export function WorkPriceFormSheet({
  open,
  mode,
  priceId,
  onClose,
  onSuccess,
}: WorkPriceFormSheetProps) {
  const { t } = useTranslation()

  const { data: priceData, isLoading: isLoadingPrice } = useWorkPrice(
    mode === 'edit' ? priceId : null,
  )

  const { data: workItems } = useReferenceOptions('/api/work-items', open)
  const { data: currencies } = useCurrencyOptions(open)

  const createMutation = useCreateWorkPrice()
  const updateMutation = useUpdateWorkPrice()

  const isCreate = mode === 'create'
  const schema = isCreate ? workPriceCreateSchema : workPriceUpdateSchema
  const isPending = createMutation.isPending || updateMutation.isPending

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<WorkPriceCreateFormValues | WorkPriceUpdateFormValues>({
    resolver: zodResolver(schema),
    mode: 'onBlur',
    defaultValues: {
      workItemId: 0,
      currencyId: 0,
      netPrice: 0,
      validFrom: '',
      validTo: '',
    },
  })

  // Reset form when price data is loaded (edit mode) or when mode changes.
  useEffect(() => {
    if (mode === 'edit' && priceData) {
      reset({
        workItemId: priceData.workItemId,
        currencyId: priceData.currencyId,
        netPrice: priceData.netPrice,
        validFrom: priceData.validFrom,
        validTo: priceData.validTo ?? '',
      })
    } else if (mode === 'create' && open) {
      reset({ workItemId: 0, currencyId: 0, netPrice: 0, validFrom: '', validTo: '' })
    }
  }, [mode, priceData, open, reset])

  const onSubmit = (values: WorkPriceCreateFormValues | WorkPriceUpdateFormValues) => {
    const payload = {
      workItemId: values.workItemId,
      currencyId: values.currencyId,
      netPrice: values.netPrice,
      validFrom: values.validFrom,
      validTo: values.validTo ? values.validTo : null,
    }
    if (isCreate) {
      createMutation.mutate(payload, { onSuccess: () => onSuccess() })
    } else {
      if (priceId == null) return
      updateMutation.mutate(
        { id: priceId, data: payload },
        { onSuccess: () => onSuccess() },
      )
    }
  }

  return (
    <Sheet open={open} onOpenChange={(isOpen) => { if (!isOpen) onClose() }}>
      <SheetContent side="right" className="flex w-full flex-col overflow-y-auto sm:max-w-lg">
        <SheetHeader>
          <SheetTitle>
            {isCreate ? t('workPrices.form.titleCreate') : t('workPrices.form.titleEdit')}
          </SheetTitle>
          <SheetDescription>
            {isCreate
              ? t('workPrices.form.descriptionCreate')
              : t('workPrices.form.descriptionEdit')}
          </SheetDescription>
        </SheetHeader>

        {mode === 'edit' && isLoadingPrice ? (
          <div className="flex-1 space-y-4 py-4">
            <div className="h-10 w-full animate-pulse rounded-md bg-muted" />
            <div className="h-10 w-full animate-pulse rounded-md bg-muted" />
            <div className="h-10 w-full animate-pulse rounded-md bg-muted" />
          </div>
        ) : (
          <form onSubmit={handleSubmit(onSubmit)} className="flex flex-1 flex-col gap-5 py-4">
            <div className="flex-1 space-y-5">
              {/* Work item select */}
              <div className="space-y-2">
                <label htmlFor="work-price-item" className="text-sm font-medium text-foreground">
                  {t('workPrices.form.workItem')}
                </label>
                <select
                  id="work-price-item"
                  {...register('workItemId')}
                  className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                >
                  <option value={0}>{t('workPrices.form.selectWorkItem')}</option>
                  {workItems?.content.map((wi) => (
                    <option key={wi.id} value={wi.id}>
                      {wi.name}
                    </option>
                  ))}
                </select>
                {errors.workItemId && (
                  <p className="text-xs text-destructive">
                    {t(errors.workItemId.message ?? '')}
                  </p>
                )}
              </div>

              {/* Currency select */}
              <div className="space-y-2">
                <label htmlFor="work-price-currency" className="text-sm font-medium text-foreground">
                  {t('workPrices.form.currency')}
                </label>
                <select
                  id="work-price-currency"
                  {...register('currencyId')}
                  className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                >
                  <option value={0}>{t('workPrices.form.selectCurrency')}</option>
                  {currencies?.content.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.code}
                    </option>
                  ))}
                </select>
                {errors.currencyId && (
                  <p className="text-xs text-destructive">{t(errors.currencyId.message ?? '')}</p>
                )}
              </div>

              {/* Net price */}
              <div className="space-y-2">
                <label htmlFor="work-price-net" className="text-sm font-medium text-foreground">
                  {t('workPrices.form.netPrice')}
                </label>
                <input
                  id="work-price-net"
                  type="number"
                  step="0.01"
                  {...register('netPrice')}
                  className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                />
                {errors.netPrice && (
                  <p className="text-xs text-destructive">{t(errors.netPrice.message ?? '')}</p>
                )}
              </div>

              {/* Valid from / Valid to — two columns on desktop */}
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div className="space-y-2">
                  <label htmlFor="work-price-from" className="text-sm font-medium text-foreground">
                    {t('workPrices.form.validFrom')}
                  </label>
                  <input
                    id="work-price-from"
                    type="date"
                    {...register('validFrom')}
                    className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {errors.validFrom && (
                    <p className="text-xs text-destructive">{t(errors.validFrom.message ?? '')}</p>
                  )}
                </div>

                <div className="space-y-2">
                  <label htmlFor="work-price-to" className="text-sm font-medium text-foreground">
                    {t('workPrices.form.validTo')}
                  </label>
                  <input
                    id="work-price-to"
                    type="date"
                    {...register('validTo')}
                    className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {errors.validTo && (
                    <p className="text-xs text-destructive">{t(errors.validTo.message ?? '')}</p>
                  )}
                </div>
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
                    ? t('workPrices.form.submitCreate')
                    : t('workPrices.form.submitEdit')}
              </button>
            </SheetFooter>
          </form>
        )}
      </SheetContent>
    </Sheet>
  )
}
