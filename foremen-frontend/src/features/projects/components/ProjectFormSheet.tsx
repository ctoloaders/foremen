/**
 * ProjectFormSheet — Sheet overlay for creating or editing a project (FOR-04-13 Req 8.4–8.9).
 *
 * Create mode: base fields (`name`, `area`, `startDate`, `endDate`, `status` select) plus the
 * {@link GoogleAddressAutocomplete} address capture, the {@link TeamMemberSelect} team multi-select,
 * and the {@link ClientBlock}. On submit it calls the custom transactional endpoint
 * (`POST /api/projects`) with base fields + `members[]` + optional `client` (Req 8.5).
 *
 * Edit mode: base fields ONLY, submitted through the generic update (`PUT /api/projects/{id}`);
 * team/client are not editable here (Req 8.6). The form is prefilled from `useProject(id)`.
 *
 * Validation (Req 8.7, 8.8): base fields + the `endDate >= startDate` refinement are enforced by
 * `projectCreateSchema` / `projectUpdateSchema` via `zodResolver`. On an invalid submit the form is
 * blocked, all entered values are retained, and a localized message is shown under each invalid
 * field (schema messages are i18n keys resolved through `t(...)`). Address/team/client state lives
 * outside the schema (they are UI-managed) and is preserved across a blocked submit.
 */
import { useEffect, useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
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
import { ApiError } from '@/lib/api-client'
import { useProject } from '../api/query-hooks'
import { useCreateProject, useUpdateProject } from '../api/mutation-hooks'
import { projectCreateSchema, projectUpdateSchema } from '../schemas/project-schema'
import type {
  ProjectCreateFormValues,
  ProjectUpdateFormValues,
} from '../schemas/project-schema'
import { PROJECT_STATUSES } from '../types'
import type {
  ClientBlockValue,
  CreateProjectRequest,
  ProjectFormMode,
  ProjectStatus,
  ProjectUpdateRequest,
} from '../types'
import { DatePicker } from '@/components/ui/date-picker'
import { GoogleAddressAutocomplete } from './GoogleAddressAutocomplete'
import { ClientBlock } from './ClientBlock'
import { TeamMemberSelect, type SelectedTeamMember } from './TeamMemberSelect'

interface ProjectFormSheetProps {
  open: boolean
  mode: ProjectFormMode
  projectId: number | null
  onClose: () => void
  onSuccess: () => void
}

/** Base-field defaults shared by create/reset and edit-prefill. `status` defaults to DRAFT. */
const EMPTY_BASE = {
  name: '',
  address: '',
  googlePlaceId: '',
  formattedAddress: '',
  latitude: null,
  longitude: null,
  area: null,
  startDate: '',
  endDate: '',
  status: 'DRAFT' as ProjectStatus,
}

export function ProjectFormSheet({
  open,
  mode,
  projectId,
  onClose,
  onSuccess,
}: Readonly<ProjectFormSheetProps>) {
  const { t } = useTranslation()

  const isCreate = mode === 'create'
  const schema = isCreate ? projectCreateSchema : projectUpdateSchema

  const { data: projectData, isLoading: isLoadingProject } = useProject(
    mode === 'edit' ? projectId : null,
  )

  const createMutation = useCreateProject()
  const updateMutation = useUpdateProject()
  const isPending = createMutation.isPending || updateMutation.isPending

  // Address text shown in the autocomplete (controlled separately from RHF so the component owns
  // its own predictions state; the resolved place fields are written into RHF via setValue).
  const [addressText, setAddressText] = useState('')
  // Team + client are UI-managed (not part of the zod schema); only used in create mode.
  const [team, setTeam] = useState<SelectedTeamMember[]>([])
  const [client, setClient] = useState<ClientBlockValue>(null)

  const {
    register,
    handleSubmit,
    reset,
    setValue,
    watch,
    formState: { errors },
  } = useForm<ProjectCreateFormValues | ProjectUpdateFormValues>({
    resolver: zodResolver(schema),
    mode: 'onBlur',
    defaultValues: EMPTY_BASE,
  })

  // Reset form/UI-state when opening (create) or when edit data arrives.
  useEffect(() => {
    if (mode === 'edit' && projectData) {
      reset({
        name: projectData.name,
        address: projectData.address ?? '',
        googlePlaceId: projectData.googlePlaceId ?? '',
        formattedAddress: projectData.formattedAddress ?? '',
        latitude: projectData.latitude,
        longitude: projectData.longitude,
        area: projectData.area,
        startDate: projectData.startDate ?? '',
        endDate: projectData.endDate ?? '',
        status: projectData.status,
      })
      setAddressText(projectData.address ?? projectData.formattedAddress ?? '')
      setTeam([])
      setClient(null)
    } else if (mode === 'create' && open) {
      reset(EMPTY_BASE)
      setAddressText('')
      setTeam([])
      setClient(null)
    }
  }, [mode, projectData, open, reset])

  /** Normalize an optional string field to `string | null` for the wire. */
  const nullable = (v: string | undefined): string | null => (v && v.length > 0 ? v : null)

  function buildClientBlock(): CreateProjectRequest['client'] {
    if (!client) return null
    if (client.kind === 'existing') {
      return { existingClientUserId: client.existingClientUserId }
    }
    return {
      newClient: {
        name: client.newClient.name,
        email: client.newClient.email,
        phone: nullable(client.newClient.phone ?? ''),
        locale: null,
      },
    }
  }

  const onSubmit = (values: ProjectCreateFormValues | ProjectUpdateFormValues) => {
    if (isCreate) {
      const payload: CreateProjectRequest = {
        name: values.name,
        address: nullable(values.address),
        googlePlaceId: nullable(values.googlePlaceId),
        formattedAddress: nullable(values.formattedAddress),
        latitude: values.latitude ?? null,
        longitude: values.longitude ?? null,
        area: values.area ?? null,
        startDate: nullable(values.startDate),
        endDate: nullable(values.endDate),
        status: values.status as ProjectStatus,
        members: team.map((m) => ({ userId: m.userId, projectRoleId: m.roleId })),
        client: buildClientBlock(),
      }
      createMutation.mutate(payload, {
        onSuccess: () => {
          toast.success(t('projects.toast.createSuccess'))
          onSuccess()
        },
        onError: (error) => {
          const message =
            error instanceof ApiError ? error.message : t('projects.toast.createError')
          toast.error(message)
        },
      })
      return
    }

    if (projectId == null) return
    const payload: ProjectUpdateRequest = {
      name: values.name,
      address: nullable(values.address),
      googlePlaceId: nullable(values.googlePlaceId),
      formattedAddress: nullable(values.formattedAddress),
      latitude: values.latitude ?? null,
      longitude: values.longitude ?? null,
      area: values.area ?? null,
      startDate: nullable(values.startDate),
      endDate: nullable(values.endDate),
      status: values.status as ProjectStatus,
    }
    updateMutation.mutate(
      { id: projectId, data: payload },
      {
        onSuccess: () => {
          toast.success(t('projects.toast.updateSuccess'))
          onSuccess()
        },
        onError: (error) => {
          const message =
            error instanceof ApiError ? error.message : t('projects.toast.updateError')
          toast.error(message)
        },
      },
    )
  }

  const statusOptions = useMemo(() => PROJECT_STATUSES, [])

  return (
    <Sheet open={open} onOpenChange={(isOpen) => { if (!isOpen) onClose() }}>
      <SheetContent side="right" className="flex w-full flex-col overflow-y-auto sm:max-w-lg">
        <SheetHeader>
          <SheetTitle>
            {isCreate ? t('projects.form.titleCreate') : t('projects.form.titleEdit')}
          </SheetTitle>
          <SheetDescription>
            {isCreate
              ? t('projects.form.descriptionCreate')
              : t('projects.form.descriptionEdit')}
          </SheetDescription>
        </SheetHeader>

        {mode === 'edit' && isLoadingProject ? (
          <div className="flex-1 space-y-4 py-4">
            <div className="h-10 w-full animate-pulse rounded-md bg-muted" />
            <div className="h-10 w-full animate-pulse rounded-md bg-muted" />
            <div className="h-10 w-full animate-pulse rounded-md bg-muted" />
          </div>
        ) : (
          <form onSubmit={handleSubmit(onSubmit)} className="flex flex-1 flex-col gap-5 py-4">
            <div className="flex-1 space-y-5">
              {/* Name */}
              <div className="space-y-2">
                <label htmlFor="project-name" className="text-sm font-medium text-foreground">
                  {t('projects.form.name')}
                </label>
                <input
                  id="project-name"
                  type="text"
                  {...register('name')}
                  className="h-9 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                />
                {errors.name && (
                  <p className="text-xs text-destructive">{t(errors.name.message ?? '')}</p>
                )}
              </div>

              {/* Address (Google autocomplete) — create + edit both capture the place fields. */}
              <div className="space-y-2">
                <label htmlFor="project-address" className="text-sm font-medium text-foreground">
                  {t('projects.form.address')}
                </label>
                <GoogleAddressAutocomplete
                  id="project-address"
                  value={addressText}
                  onChange={(text) => {
                    setAddressText(text)
                    setValue('address', text, { shouldValidate: false })
                  }}
                  onSelect={(selection) => {
                    setValue('googlePlaceId', selection.googlePlaceId)
                    setValue('formattedAddress', selection.formattedAddress)
                    setValue('latitude', selection.latitude)
                    setValue('longitude', selection.longitude)
                    setValue('address', selection.formattedAddress || addressText)
                    setAddressText(selection.formattedAddress || addressText)
                  }}
                  disabled={isPending}
                  error={errors.address ? t(errors.address.message ?? '') : undefined}
                />
              </div>

              {/* Area */}
              <div className="space-y-2">
                <label htmlFor="project-area" className="text-sm font-medium text-foreground">
                  {t('projects.form.area')}
                </label>
                <input
                  id="project-area"
                  type="number"
                  step="0.01"
                  {...register('area')}
                  className="h-9 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                />
                {errors.area && (
                  <p className="text-xs text-destructive">{t(errors.area.message ?? '')}</p>
                )}
              </div>

              {/*
                Start / End date — shared Calendar-based DatePicker (FOR-04-bugs Bug 5 / Req 2.5),
                replacing the previous native browser date inputs. The values are read/written
                through react-hook-form (watch/setValue) so the existing zod validation and the
                endDate >= startDate refinement keep working and edit-mode prefill still loads. The
                DatePicker emits the same 'YYYY-MM-DD' ISO string the payload + schema expect, so the
                submitted wire format is unchanged.

                NOTE: the project form is the only base-entity form with date fields — the room form
                (RoomFormSheet) and the work-catalog forms (WorkItemFormSheet) have no date inputs,
                so no other form needs the shared DatePicker.
              */}
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div className="space-y-2">
                  <label htmlFor="project-start" className="text-sm font-medium text-foreground">
                    {t('projects.form.startDate')}
                  </label>
                  <DatePicker
                    id="project-start"
                    aria-label={t('projects.form.startDate')}
                    value={watch('startDate')}
                    onChange={(next) =>
                      setValue('startDate', next, { shouldValidate: true, shouldDirty: true })
                    }
                    disabled={isPending}
                  />
                  {errors.startDate && (
                    <p className="text-xs text-destructive">{t(errors.startDate.message ?? '')}</p>
                  )}
                </div>

                <div className="space-y-2">
                  <label htmlFor="project-end" className="text-sm font-medium text-foreground">
                    {t('projects.form.endDate')}
                  </label>
                  <DatePicker
                    id="project-end"
                    aria-label={t('projects.form.endDate')}
                    value={watch('endDate')}
                    onChange={(next) =>
                      setValue('endDate', next, { shouldValidate: true, shouldDirty: true })
                    }
                    disabled={isPending}
                  />
                  {errors.endDate && (
                    <p className="text-xs text-destructive">{t(errors.endDate.message ?? '')}</p>
                  )}
                </div>
              </div>

              {/* Status select */}
              <div className="space-y-2">
                <label htmlFor="project-status" className="text-sm font-medium text-foreground">
                  {t('projects.form.status')}
                </label>
                <select
                  id="project-status"
                  {...register('status')}
                  className="h-9 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {statusOptions.map((status) => (
                    <option key={status} value={status}>
                      {t(`projects.status.${status}`)}
                    </option>
                  ))}
                </select>
                {errors.status && (
                  <p className="text-xs text-destructive">{t(errors.status.message ?? '')}</p>
                )}
              </div>

              {/* Team + Client — create mode only (team/client not editable via generic update). */}
              {isCreate && (
                <>
                  <div className="space-y-2">
                    <label className="text-sm font-medium text-foreground">
                      {t('projects.form.team.label')}
                    </label>
                    <TeamMemberSelect value={team} onChange={setTeam} disabled={isPending} />
                    <p className="text-xs text-muted-foreground">
                      {t('projects.form.team.hint')}
                    </p>
                  </div>

                  <ClientBlock value={client} onChange={setClient} disabled={isPending} />
                </>
              )}
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
                    ? t('projects.form.submitCreate')
                    : t('projects.form.submitEdit')}
              </button>
            </SheetFooter>
          </form>
        )}
      </SheetContent>
    </Sheet>
  )
}
