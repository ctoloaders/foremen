/**
 * RoomTypeFormSheet — Sheet overlay for creating or editing a room type.
 *
 * Create mode: all fields empty, `code` editable, `active` defaults to true.
 * Edit mode: pre-populated from useRoomType(id); `code` shown read-only
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
import { useRoomType } from '../api/query-hooks'
import { useCreateRoomType, useUpdateRoomType } from '../api/mutation-hooks'
import {
  roomTypeCreateSchema,
  roomTypeUpdateSchema,
} from '../schemas/room-type-schema'
import type {
  RoomTypeCreateFormValues,
  RoomTypeUpdateFormValues,
} from '../schemas/room-type-schema'
import type { RoomTypeFormMode } from '../types'

interface RoomTypeFormSheetProps {
  open: boolean
  mode: RoomTypeFormMode
  roomTypeId: number | null
  onClose: () => void
  onSuccess: () => void
}

export function RoomTypeFormSheet({
  open,
  mode,
  roomTypeId,
  onClose,
  onSuccess,
}: RoomTypeFormSheetProps) {
  const { t } = useTranslation()

  // Fetch room type data in edit mode
  const { data: roomTypeData, isLoading: isLoadingRoomType } = useRoomType(
    mode === 'edit' ? roomTypeId : null,
  )

  const createMutation = useCreateRoomType()
  const updateMutation = useUpdateRoomType()

  const isCreate = mode === 'create'
  const schema = isCreate ? roomTypeCreateSchema : roomTypeUpdateSchema
  const isPending = createMutation.isPending || updateMutation.isPending

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<RoomTypeCreateFormValues | RoomTypeUpdateFormValues>({
    resolver: zodResolver(schema),
    mode: 'onBlur',
    defaultValues: isCreate
      ? { code: '', nameRU: '', namePL: '', active: true }
      : { nameRU: '', namePL: '', active: true },
  })

  // Reset form when room type data is loaded (edit mode) or when mode changes.
  useEffect(() => {
    if (mode === 'edit' && roomTypeData) {
      reset({
        nameRU: roomTypeData.nameRU,
        namePL: roomTypeData.namePL,
        active: roomTypeData.active,
      })
    } else if (mode === 'create' && open) {
      reset({ code: '', nameRU: '', namePL: '', active: true })
    }
  }, [mode, roomTypeData, open, reset])

  const onSubmit = (
    values: RoomTypeCreateFormValues | RoomTypeUpdateFormValues,
  ) => {
    if (isCreate) {
      const data = values as RoomTypeCreateFormValues
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
      if (roomTypeId == null) return
      const data = values as RoomTypeUpdateFormValues
      updateMutation.mutate(
        {
          id: roomTypeId,
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
              ? t('roomTypes.form.titleCreate')
              : t('roomTypes.form.titleEdit')}
          </SheetTitle>
          <SheetDescription>
            {isCreate
              ? t('roomTypes.form.descriptionCreate')
              : t('roomTypes.form.descriptionEdit')}
          </SheetDescription>
        </SheetHeader>

        {/* Loading placeholder in edit mode */}
        {mode === 'edit' && isLoadingRoomType ? (
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
                  <label htmlFor="room-type-code" className="text-sm font-medium text-foreground">
                    {t('roomTypes.form.code')}
                  </label>
                  <input
                    id="room-type-code"
                    type="text"
                    {...register(
                      'code' as keyof (
                        | RoomTypeCreateFormValues
                        | RoomTypeUpdateFormValues
                      ),
                    )}
                    placeholder="kuchnia"
                    className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {'code' in errors && errors.code && (
                    <p className="text-xs text-destructive">{t(errors.code.message ?? '')}</p>
                  )}
                </div>
              )}

              {/* Edit mode: code shown read-only (immutable) */}
              {mode === 'edit' && roomTypeData && (
                <div className="space-y-2">
                  <label className="text-sm font-medium text-foreground">
                    {t('roomTypes.form.code')}
                  </label>
                  <input
                    type="text"
                    value={roomTypeData.code}
                    disabled
                    className="h-10 w-full rounded-md border border-border bg-muted px-3 text-sm text-muted-foreground disabled:cursor-not-allowed"
                  />
                </div>
              )}

              {/* Name RU / Name PL — two columns on desktop */}
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div className="space-y-2">
                  <label htmlFor="room-type-nameRU" className="text-sm font-medium text-foreground">
                    {t('roomTypes.form.nameRU')}
                  </label>
                  <input
                    id="room-type-nameRU"
                    type="text"
                    {...register('nameRU')}
                    className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {errors.nameRU && (
                    <p className="text-xs text-destructive">{t(errors.nameRU.message ?? '')}</p>
                  )}
                </div>

                <div className="space-y-2">
                  <label htmlFor="room-type-namePL" className="text-sm font-medium text-foreground">
                    {t('roomTypes.form.namePL')}
                  </label>
                  <input
                    id="room-type-namePL"
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
                  id="room-type-active"
                  type="checkbox"
                  {...register('active')}
                  className="h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-ring"
                />
                <label htmlFor="room-type-active" className="text-sm font-medium text-foreground">
                  {t('roomTypes.form.active')}
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
                    ? t('roomTypes.form.submitCreate')
                    : t('roomTypes.form.submitEdit')}
              </button>
            </SheetFooter>
          </form>
        )}
      </SheetContent>
    </Sheet>
  )
}
