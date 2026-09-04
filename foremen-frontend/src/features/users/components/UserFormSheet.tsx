/**
 * UserFormSheet — Sheet overlay for creating or editing a user.
 *
 * Create mode: all fields empty, active defaults to true.
 * Edit mode: fetches user via useUser(id), pre-populates form,
 *            shows UserFormSkeleton while loading.
 *
 * Validates on blur (individual fields) and on submit (entire form).
 * Displays inline error messages below invalid fields (localized via i18n).
 *
 * Requirements: 2.1-2.9, 3.1-3.7, 8.4, 11.1-11.5, 12.4, 13.2, 13.3, 15.2, 15.3
 */
import { useEffect } from 'react'
import { useForm, Controller } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Loader2 } from 'lucide-react'

import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
  SheetFooter,
} from '@/components/ui/sheet'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'

import { useUser } from '../api/query-hooks'
import { useCreateUser, useUpdateUser, isEmailConflictError } from '../api/mutation-hooks'
import { userFormSchema } from '../schemas/user-schema'
import { UserFormSkeleton } from './UserFormSkeleton'
import { PhoneInput } from './PhoneInput'
import { RoleSelect } from './RoleSelect'
import type { UserFormValues } from '../schemas/user-schema'
import type { UserFormMode } from '../types'

interface UserFormSheetProps {
  open: boolean
  mode: UserFormMode
  userId: number | null
  onClose: () => void
  onSuccess: () => void
}

export function UserFormSheet({
  open,
  mode,
  userId,
  onClose,
  onSuccess,
}: UserFormSheetProps) {
  const { t } = useTranslation()

  // Fetch user data in edit mode
  const { data: userData, isLoading: isLoadingUser } = useUser(
    mode === 'edit' ? userId : null,
  )

  const createMutation = useCreateUser()
  const updateMutation = useUpdateUser()

  const isCreate = mode === 'create'
  const isPending = createMutation.isPending || updateMutation.isPending

  const {
    register,
    handleSubmit,
    control,
    reset,
    formState: { errors },
  } = useForm<UserFormValues>({
    resolver: zodResolver(userFormSchema),
    mode: 'onBlur',
    defaultValues: {
      name: '',
      email: '',
      phone: '',
      roleId: undefined,
      locale: undefined,
      active: true,
    },
  })

  // Reset form when user data is loaded (edit mode) or when mode/open changes
  useEffect(() => {
    if (mode === 'edit' && userData) {
      reset({
        name: userData.name,
        email: userData.email,
        phone: userData.phone ?? '',
        roleId: userData.roleId,
        locale: userData.locale as 'ru' | 'pl',
        active: userData.active,
      })
    } else if (mode === 'create' && open) {
      reset({
        name: '',
        email: '',
        phone: '',
        roleId: undefined,
        locale: undefined,
        active: true,
      })
    }
  }, [mode, userData, open, reset])

  const onSubmit = (values: UserFormValues) => {
    if (isCreate) {
      createMutation.mutate(
        {
          name: values.name,
          email: values.email,
          phone: values.phone || null,
          roleId: values.roleId,
          locale: values.locale,
        },
        {
          onSuccess: () => {
            onSuccess()
          },
          onError: (error) => {
            if (isEmailConflictError(error)) {
              toast.error(t('users.toast.emailExists'))
            } else {
              const message =
                error instanceof Error ? error.message : t('users.errors.network')
              toast.error(message)
            }
          },
        },
      )
    } else {
      if (userId == null) return
      updateMutation.mutate(
        {
          id: userId,
          data: {
            name: values.name,
            email: values.email,
            phone: values.phone || null,
            roleId: values.roleId,
            locale: values.locale,
            active: values.active,
          },
        },
        {
          onSuccess: () => {
            onSuccess()
          },
          onError: (error) => {
            if (isEmailConflictError(error)) {
              toast.error(t('users.toast.emailExists'))
            } else {
              const message =
                error instanceof Error ? error.message : t('users.errors.network')
              toast.error(message)
            }
          },
        },
      )
    }
  }

  return (
    <Sheet open={open} onOpenChange={(isOpen) => { if (!isOpen) onClose() }}>
      <SheetContent side="right" className="flex w-full flex-col overflow-y-auto sm:max-w-lg">
        <SheetHeader>
          <SheetTitle>
            {isCreate ? t('users.form.titleCreate') : t('users.form.titleEdit')}
          </SheetTitle>
          <SheetDescription>
            {isCreate
              ? t('users.form.descriptionCreate')
              : t('users.form.descriptionEdit')}
          </SheetDescription>
        </SheetHeader>

        {/* Loading skeleton in edit mode */}
        {mode === 'edit' && isLoadingUser ? (
          <div className="flex-1 py-4">
            <UserFormSkeleton />
          </div>
        ) : (
          <form
            onSubmit={handleSubmit(onSubmit)}
            className="flex flex-1 flex-col gap-5 py-4"
          >
            <div className="flex-1 space-y-5">
              {/* Name / Email — two columns on desktop */}
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                {/* Name */}
                <div className="space-y-2">
                  <label
                    htmlFor="user-name"
                    className="text-sm font-medium text-foreground"
                  >
                    {t('users.form.name')}
                  </label>
                  <input
                    id="user-name"
                    type="text"
                    {...register('name')}
                    className="h-9 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {errors.name && (
                    <p className="text-xs text-destructive">
                      {t(errors.name.message ?? '')}
                    </p>
                  )}
                </div>

                {/* Email */}
                <div className="space-y-2">
                  <label
                    htmlFor="user-email"
                    className="text-sm font-medium text-foreground"
                  >
                    {t('users.form.email')}
                  </label>
                  <input
                    id="user-email"
                    type="email"
                    {...register('email')}
                    className="h-9 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {errors.email && (
                    <p className="text-xs text-destructive">
                      {t(errors.email.message ?? '')}
                    </p>
                  )}
                </div>
              </div>

              {/* Phone / Locale — two columns on desktop */}
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                {/* Phone */}
                <div className="space-y-2">
                  <label
                    htmlFor="user-phone"
                    className="text-sm font-medium text-foreground"
                  >
                    {t('users.form.phone')}
                  </label>
                  <Controller
                    name="phone"
                    control={control}
                    render={({ field }) => (
                      <PhoneInput
                        value={field.value}
                        onChange={(val) => field.onChange(val ?? '')}
                        defaultCountry="PL"
                        error={errors.phone ? t(errors.phone.message ?? '') : undefined}
                        disabled={isPending}
                      />
                    )}
                  />
                </div>

                {/* Locale */}
                <div className="space-y-2">
                  <label
                    htmlFor="user-locale"
                    className="text-sm font-medium text-foreground"
                  >
                    {t('users.form.locale')}
                  </label>
                  <Controller
                    name="locale"
                    control={control}
                    render={({ field }) => (
                      <Select
                        value={field.value ?? ''}
                        onValueChange={(val) => field.onChange(val)}
                        disabled={isPending}
                      >
                        <SelectTrigger
                          id="user-locale"
                          className={errors.locale ? 'border-destructive' : ''}
                        >
                          <SelectValue placeholder={t('users.form.localePlaceholder')} />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value="pl">Polski (PL)</SelectItem>
                          <SelectItem value="ru">Русский (RU)</SelectItem>
                        </SelectContent>
                      </Select>
                    )}
                  />
                  {errors.locale && (
                    <p className="text-xs text-destructive">
                      {t(errors.locale.message ?? '')}
                    </p>
                  )}
                </div>
              </div>

              {/* Role */}
              <div className="space-y-2">
                <label className="text-sm font-medium text-foreground">
                  {t('users.form.role')}
                </label>
                <Controller
                  name="roleId"
                  control={control}
                  render={({ field }) => (
                    <RoleSelect
                      value={field.value}
                      onChange={(val) => field.onChange(val)}
                      error={errors.roleId ? t(errors.roleId.message ?? '') : undefined}
                      disabled={isPending}
                      currentRoleName={mode === 'edit' ? userData?.roleName : undefined}
                    />
                  )}
                />
                {errors.roleId && (
                  <p className="text-xs text-destructive">
                    {t(errors.roleId.message ?? '')}
                  </p>
                )}
              </div>

              {/* Active checkbox */}
              <div className="flex items-center gap-3">
                <input
                  id="user-active"
                  type="checkbox"
                  {...register('active')}
                  disabled={isPending}
                  className="h-4 w-4 rounded border border-border bg-background text-primary focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                />
                <label
                  htmlFor="user-active"
                  className="text-sm font-medium text-foreground"
                >
                  {t('users.form.active')}
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
                    ? t('users.form.submitCreate')
                    : t('users.form.submitEdit')}
              </button>
            </SheetFooter>
          </form>
        )}
      </SheetContent>
    </Sheet>
  )
}
