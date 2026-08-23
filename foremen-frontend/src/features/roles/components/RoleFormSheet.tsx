/**
 * RoleFormSheet — Sheet overlay for creating or editing a role.
 *
 * Create mode: all fields empty, code editable.
 * Edit mode: pre-populated from useRole(id), code field disabled (immutable),
 *            system roles have name fields disabled (only descriptions editable).
 *
 * Validates on blur (individual fields) and on submit (entire form).
 * Displays inline error messages below invalid fields (localized via i18n).
 *
 * Requirements: 2.1-2.7, 3.1-3.6, 7.4, 10.1-10.5, 12.3, 16.2
 */
import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useTranslation } from 'react-i18next'

import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
  SheetFooter,
} from '@/components/ui/sheet'
import { Loader2 } from 'lucide-react'
import { useRole } from '../api/query-hooks'
import { useCreateRole, useUpdateRole } from '../api/mutation-hooks'
import { roleCreateSchema, roleUpdateSchema } from '../schemas/role-schema'
import { RoleFormSkeleton } from './RoleFormSkeleton'
import type { RoleCreateFormValues, RoleUpdateFormValues } from '../schemas/role-schema'
import type { RoleFormMode } from '../types'

interface RoleFormSheetProps {
  open: boolean
  mode: RoleFormMode
  roleId: number | null
  onClose: () => void
  onSuccess: () => void
}

export function RoleFormSheet({
  open,
  mode,
  roleId,
  onClose,
  onSuccess,
}: RoleFormSheetProps) {
  const { t } = useTranslation()

  // Fetch role data in edit mode
  const {
    data: roleData,
    isLoading: isLoadingRole,
  } = useRole(mode === 'edit' ? roleId : null)

  const createMutation = useCreateRole()
  const updateMutation = useUpdateRole()

  const isCreate = mode === 'create'
  const schema = isCreate ? roleCreateSchema : roleUpdateSchema
  const isPending = createMutation.isPending || updateMutation.isPending

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<RoleCreateFormValues | RoleUpdateFormValues>({
    resolver: zodResolver(schema),
    mode: 'onBlur',
    defaultValues: isCreate
      ? { code: '', nameRU: '', namePL: '', descriptionRU: '', descriptionPL: '' }
      : { nameRU: '', namePL: '', descriptionRU: '', descriptionPL: '' },
  })

  // Reset form when role data is loaded (edit mode) or when mode changes
  useEffect(() => {
    if (mode === 'edit' && roleData) {
      reset({
        nameRU: roleData.nameRU,
        namePL: roleData.namePL,
        descriptionRU: roleData.descriptionRU ?? '',
        descriptionPL: roleData.descriptionPL ?? '',
      })
    } else if (mode === 'create' && open) {
      reset({
        code: '',
        nameRU: '',
        namePL: '',
        descriptionRU: '',
        descriptionPL: '',
      })
    }
  }, [mode, roleData, open, reset])

  const onSubmit = (values: RoleCreateFormValues | RoleUpdateFormValues) => {
    if (isCreate) {
      const data = values as RoleCreateFormValues
      createMutation.mutate(
        {
          code: data.code,
          nameRU: data.nameRU,
          namePL: data.namePL,
          descriptionRU: data.descriptionRU || undefined,
          descriptionPL: data.descriptionPL || undefined,
        },
        {
          onSuccess: () => {
            onSuccess()
          },
        },
      )
    } else {
      if (roleId == null) return
      const data = values as RoleUpdateFormValues
      updateMutation.mutate(
        {
          id: roleId,
          data: {
            nameRU: data.nameRU,
            namePL: data.namePL,
            descriptionRU: data.descriptionRU || undefined,
            descriptionPL: data.descriptionPL || undefined,
          },
        },
        {
          onSuccess: () => {
            onSuccess()
          },
        },
      )
    }
  }

  const isSystemRole = mode === 'edit' && roleData?.system === true

  return (
    <Sheet open={open} onOpenChange={(isOpen) => { if (!isOpen) onClose() }}>
      <SheetContent side="right" className="flex w-full flex-col overflow-y-auto sm:max-w-lg">
        <SheetHeader>
          <SheetTitle>
            {isCreate ? t('roles.form.titleCreate') : t('roles.form.titleEdit')}
          </SheetTitle>
          <SheetDescription>
            {isCreate
              ? t('roles.form.descriptionCreate')
              : t('roles.form.descriptionEdit')}
          </SheetDescription>
        </SheetHeader>

        {/* Loading skeleton in edit mode */}
        {mode === 'edit' && isLoadingRole ? (
          <div className="flex-1 py-4">
            <RoleFormSkeleton />
          </div>
        ) : (
          <form
            onSubmit={handleSubmit(onSubmit)}
            className="flex flex-1 flex-col gap-5 py-4"
          >
            <div className="flex-1 space-y-5">
              {/* Code field — only in create mode */}
              {isCreate && (
                <div className="space-y-2">
                  <label
                    htmlFor="role-code"
                    className="text-sm font-medium text-foreground"
                  >
                    {t('roles.form.code')}
                  </label>
                  <input
                    id="role-code"
                    type="text"
                    {...register('code' as keyof (RoleCreateFormValues | RoleUpdateFormValues))}
                    placeholder="ROLE_CODE"
                    className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {'code' in errors && errors.code && (
                    <p className="text-xs text-destructive">
                      {t(errors.code.message ?? '')}
                    </p>
                  )}
                </div>
              )}

              {/* Edit mode: show code as read-only info */}
              {mode === 'edit' && roleData && (
                <div className="space-y-2">
                  <label className="text-sm font-medium text-foreground">
                    {t('roles.form.code')}
                  </label>
                  <input
                    type="text"
                    value={roleData.code}
                    disabled
                    className="h-10 w-full rounded-md border border-border bg-muted px-3 text-sm text-muted-foreground disabled:cursor-not-allowed"
                  />
                </div>
              )}

              {/* Name RU / Name PL — two columns on desktop */}
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div className="space-y-2">
                  <label
                    htmlFor="role-nameRU"
                    className="text-sm font-medium text-foreground"
                  >
                    {t('roles.form.nameRU')}
                  </label>
                  <input
                    id="role-nameRU"
                    type="text"
                    {...register('nameRU')}
                    disabled={isSystemRole}
                    className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {errors.nameRU && (
                    <p className="text-xs text-destructive">
                      {t(errors.nameRU.message ?? '')}
                    </p>
                  )}
                </div>

                <div className="space-y-2">
                  <label
                    htmlFor="role-namePL"
                    className="text-sm font-medium text-foreground"
                  >
                    {t('roles.form.namePL')}
                  </label>
                  <input
                    id="role-namePL"
                    type="text"
                    {...register('namePL')}
                    disabled={isSystemRole}
                    className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {errors.namePL && (
                    <p className="text-xs text-destructive">
                      {t(errors.namePL.message ?? '')}
                    </p>
                  )}
                </div>
              </div>

              {/* Description RU */}
              <div className="space-y-2">
                <label
                  htmlFor="role-descriptionRU"
                  className="text-sm font-medium text-foreground"
                >
                  {t('roles.form.descriptionRU')}
                </label>
                <textarea
                  id="role-descriptionRU"
                  rows={3}
                  {...register('descriptionRU')}
                  className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                />
                {errors.descriptionRU && (
                  <p className="text-xs text-destructive">
                    {t(errors.descriptionRU.message ?? '')}
                  </p>
                )}
              </div>

              {/* Description PL */}
              <div className="space-y-2">
                <label
                  htmlFor="role-descriptionPL"
                  className="text-sm font-medium text-foreground"
                >
                  {t('roles.form.descriptionPL')}
                </label>
                <textarea
                  id="role-descriptionPL"
                  rows={3}
                  {...register('descriptionPL')}
                  className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                />
                {errors.descriptionPL && (
                  <p className="text-xs text-destructive">
                    {t(errors.descriptionPL.message ?? '')}
                  </p>
                )}
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
                    ? t('roles.form.submitCreate')
                    : t('roles.form.submitEdit')}
              </button>
            </SheetFooter>
          </form>
        )}
      </SheetContent>
    </Sheet>
  )
}
