/**
 * RoomFormSheet — Sheet overlay for creating or editing a room.
 *
 * Create mode: all fields empty. Edit mode: pre-populated from useRoom(id).
 *
 * Fields:
 * - project selector (reference to PROJECTS via /api/projects)
 * - room-type selector (reference to ROOM_TYPES via /api/room-types)
 * - optional `label`, `ceilingHeight`, `internalCorners`
 * - a per-wall openings editor (RoomOpeningsEditor) that produces the room geometry
 * - a manual-mode toggle: when geometry is present the five derived metrics
 *   (floorArea/wallArea/perimeter/doorArea/windowArea) are read-only and labeled
 *   "Calculated"; with no geometry those become editable "Manual" inputs.
 *
 * Geometry presence is driven by the openings editor: as soon as at least one wall
 * exists the room has geometry, so the derived metrics are shown read-only. With no
 * walls the form is in manual mode and the metrics are editable.
 *
 * Validation runs via a zod resolver (onBlur + onSubmit). Submission is blocked on
 * any invalid field, entered values are retained, and a localized message is shown
 * under each invalid field.
 */
import { useEffect, useMemo, useState } from 'react'
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
import { useRoom } from '../api/query-hooks'
import { useCreateRoom, useUpdateRoom } from '../api/mutation-hooks'
import { roomCreateSchema, roomUpdateSchema } from '../schemas/room-schema'
import type { RoomCreateFormValues, RoomUpdateFormValues } from '../schemas/room-schema'
import type {
  PaginatedResponse,
  RoomCreateRequest,
  RoomFormMode,
  RoomGeometry,
} from '../types'
import { RoomSourceBadge } from './RoomSourceBadge'
import { RoomOpeningsEditor } from './RoomOpeningsEditor'
import type { EditorWall } from './RoomOpeningsEditor'

interface RoomFormSheetProps {
  open: boolean
  mode: RoomFormMode
  roomId: number | null
  onClose: () => void
  onSuccess: () => void
}

/** Minimal option row from a reference list endpoint. */
interface ReferenceOption {
  id: number
  name?: string
  code?: string
}

/** Reference options fetched from a managed-entity list endpoint (label = `name`). */
function useReferenceOptions(path: string, sortField: string, enabled: boolean) {
  return useQuery({
    queryKey: ['rooms-options', path],
    queryFn: () =>
      apiRequest<PaginatedResponse<ReferenceOption>>(
        `${path}?page=0&size=200&sort=${sortField},asc`,
      ),
    enabled,
    staleTime: 60_000,
  })
}

/** Number-or-empty helper: "" (or non-finite) → null, otherwise the number. */
function numOrNull(v: string): number | null {
  if (v.trim() === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** Build the geometry payload from editor walls, or null when there are no walls. */
function wallsToGeometry(walls: EditorWall[]): RoomGeometry | null {
  if (walls.length === 0) return null
  return {
    // The interactive polygon vertex editor is deferred (FOR-05); until then we
    // carry only the per-wall openings the user entered. When vertices are
    // supplied by a future editor they flow through here unchanged.
    vertices: [],
    walls: walls.map((w) => ({
      wallGap: numOrNull(w.wallGap),
      finishGap: numOrNull(w.finishGap),
      openings: w.openings.map((o) => ({
        type: o.type,
        count: Number(o.count),
        height: Number(o.height),
        width: Number(o.width),
      })),
    })),
  }
}

/** Turn stored geometry back into editable walls for edit mode. */
function geometryToWalls(geometry: RoomGeometry | null | undefined): EditorWall[] {
  if (!geometry?.walls) return []
  return geometry.walls.map((w) => ({
    wallGap: w.wallGap != null ? String(w.wallGap) : '',
    finishGap: w.finishGap != null ? String(w.finishGap) : '',
    openings: w.openings.map((o) => ({
      type: o.type,
      count: String(o.count),
      height: String(o.height),
      width: String(o.width),
    })),
  }))
}

const emptyDefaults: RoomCreateFormValues = {
  projectId: 0,
  roomTypeId: 0,
  label: '',
  ceilingHeight: null,
  internalCorners: null,
  geometry: null,
  floorArea: null,
  wallArea: null,
  perimeter: null,
  doorArea: null,
  windowArea: null,
}

export function RoomFormSheet({
  open,
  mode,
  roomId,
  onClose,
  onSuccess,
}: Readonly<RoomFormSheetProps>) {
  const { t } = useTranslation()

  const { data: roomData, isLoading: isLoadingRoom } = useRoom(
    mode === 'edit' ? roomId : null,
  )

  const { data: projects } = useReferenceOptions('/api/projects', 'name', open)
  const { data: roomTypes } = useReferenceOptions('/api/room-types', 'name', open)

  const createMutation = useCreateRoom()
  const updateMutation = useUpdateRoom()

  const isCreate = mode === 'create'
  const schema = isCreate ? roomCreateSchema : roomUpdateSchema
  const isPending = createMutation.isPending || updateMutation.isPending

  // Per-wall openings state (drives geometry presence + manual vs calculated mode).
  const [walls, setWalls] = useState<EditorWall[]>([])
  const hasGeometry = walls.length > 0

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<RoomCreateFormValues | RoomUpdateFormValues>({
    resolver: zodResolver(schema),
    mode: 'onBlur',
    defaultValues: emptyDefaults,
  })

  // Reset form when room data loads (edit) or when opening in create mode.
  useEffect(() => {
    if (mode === 'edit' && roomData) {
      reset({
        projectId: roomData.projectId,
        roomTypeId: roomData.roomTypeId,
        label: roomData.label ?? '',
        ceilingHeight: roomData.ceilingHeight,
        internalCorners: roomData.internalCorners,
        geometry: roomData.geometry,
        floorArea: roomData.floorArea,
        wallArea: roomData.wallArea,
        perimeter: roomData.perimeter,
        doorArea: roomData.doorArea,
        windowArea: roomData.windowArea,
      })
      setWalls(geometryToWalls(roomData.geometry))
    } else if (mode === 'create' && open) {
      reset(emptyDefaults)
      setWalls([])
    }
  }, [mode, roomData, open, reset])

  // Derived (read-only "Calculated") metric values shown when geometry is present.
  // In edit mode these come from the persisted, backend-calculated numerics.
  const calculated = useMemo(
    () => ({
      floorArea: roomData?.floorArea ?? null,
      wallArea: roomData?.wallArea ?? null,
      perimeter: roomData?.perimeter ?? null,
      doorArea: roomData?.doorArea ?? null,
      windowArea: roomData?.windowArea ?? null,
    }),
    [roomData],
  )

  const onSubmit = (values: RoomCreateFormValues | RoomUpdateFormValues) => {
    const geometry = wallsToGeometry(walls)
    const payload: RoomCreateRequest = {
      projectId: values.projectId,
      roomTypeId: values.roomTypeId,
      label: values.label ? values.label : null,
      ceilingHeight: values.ceilingHeight ?? null,
      internalCorners: values.internalCorners ?? null,
      geometry,
      // Manual metrics are only meaningful (and only sent) when no geometry is
      // present; with geometry the backend recomputes and ignores these.
      floorArea: geometry ? null : (values.floorArea ?? null),
      wallArea: geometry ? null : (values.wallArea ?? null),
      perimeter: geometry ? null : (values.perimeter ?? null),
      doorArea: geometry ? null : (values.doorArea ?? null),
      windowArea: geometry ? null : (values.windowArea ?? null),
    }
    if (isCreate) {
      createMutation.mutate(payload, { onSuccess: () => onSuccess() })
    } else {
      if (roomId == null) return
      updateMutation.mutate({ id: roomId, data: payload }, { onSuccess: () => onSuccess() })
    }
  }

  const inputClass =
    'h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50'
  const selectClass =
    'h-10 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50'

  // The five derived metrics, rendered either as read-only "Calculated" (geometry
  // present) or editable "Manual" inputs (no geometry).
  const metricFields = [
    { key: 'floorArea', label: t('rooms.form.floorArea'), calc: calculated.floorArea },
    { key: 'wallArea', label: t('rooms.form.wallArea'), calc: calculated.wallArea },
    { key: 'perimeter', label: t('rooms.form.perimeter'), calc: calculated.perimeter },
    { key: 'doorArea', label: t('rooms.form.doorArea'), calc: calculated.doorArea },
    { key: 'windowArea', label: t('rooms.form.windowArea'), calc: calculated.windowArea },
  ] as const

  return (
    <Sheet open={open} onOpenChange={(isOpen) => { if (!isOpen) onClose() }}>
      <SheetContent side="right" className="flex w-full flex-col overflow-y-auto sm:max-w-lg">
        <SheetHeader>
          <SheetTitle>
            {isCreate ? t('rooms.form.titleCreate') : t('rooms.form.titleEdit')}
          </SheetTitle>
          <SheetDescription>
            {isCreate
              ? t('rooms.form.descriptionCreate')
              : t('rooms.form.descriptionEdit')}
          </SheetDescription>
        </SheetHeader>

        {mode === 'edit' && isLoadingRoom ? (
          <div className="flex-1 space-y-4 py-4">
            <div className="h-10 w-full animate-pulse rounded-md bg-muted" />
            <div className="h-10 w-full animate-pulse rounded-md bg-muted" />
            <div className="h-10 w-full animate-pulse rounded-md bg-muted" />
          </div>
        ) : (
          <form onSubmit={handleSubmit(onSubmit)} className="flex flex-1 flex-col gap-5 py-4">
            <div className="flex-1 space-y-5">
              {/* Project select */}
              <div className="space-y-2">
                <label htmlFor="room-project" className="text-sm font-medium text-foreground">
                  {t('rooms.form.project')}
                </label>
                <select id="room-project" {...register('projectId')} className={selectClass}>
                  <option value={0}>{t('rooms.form.selectProject')}</option>
                  {projects?.content.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name ?? p.code}
                    </option>
                  ))}
                </select>
                {errors.projectId && (
                  <p className="text-xs text-destructive">{t(errors.projectId.message ?? '')}</p>
                )}
              </div>

              {/* Room type select */}
              <div className="space-y-2">
                <label htmlFor="room-type" className="text-sm font-medium text-foreground">
                  {t('rooms.form.roomType')}
                </label>
                <select id="room-type" {...register('roomTypeId')} className={selectClass}>
                  <option value={0}>{t('rooms.form.selectRoomType')}</option>
                  {roomTypes?.content.map((rt) => (
                    <option key={rt.id} value={rt.id}>
                      {rt.name ?? rt.code}
                    </option>
                  ))}
                </select>
                {errors.roomTypeId && (
                  <p className="text-xs text-destructive">
                    {t(errors.roomTypeId.message ?? '')}
                  </p>
                )}
              </div>

              {/* Label */}
              <div className="space-y-2">
                <label htmlFor="room-label" className="text-sm font-medium text-foreground">
                  {t('rooms.form.label')}
                </label>
                <input
                  id="room-label"
                  type="text"
                  {...register('label')}
                  className={inputClass}
                />
                {errors.label && (
                  <p className="text-xs text-destructive">{t(errors.label.message ?? '')}</p>
                )}
              </div>

              {/* Ceiling height + internal corners */}
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div className="space-y-2">
                  <label htmlFor="room-ceiling" className="text-sm font-medium text-foreground">
                    {t('rooms.form.ceilingHeight')}
                  </label>
                  <input
                    id="room-ceiling"
                    type="number"
                    step="0.01"
                    min="0"
                    {...register('ceilingHeight')}
                    className={inputClass}
                  />
                  {errors.ceilingHeight && (
                    <p className="text-xs text-destructive">
                      {t(errors.ceilingHeight.message ?? '')}
                    </p>
                  )}
                </div>
                <div className="space-y-2">
                  <label htmlFor="room-corners" className="text-sm font-medium text-foreground">
                    {t('rooms.form.internalCorners')}
                  </label>
                  <input
                    id="room-corners"
                    type="number"
                    step="1"
                    min="0"
                    {...register('internalCorners')}
                    className={inputClass}
                  />
                  {errors.internalCorners && (
                    <p className="text-xs text-destructive">
                      {t(errors.internalCorners.message ?? '')}
                    </p>
                  )}
                </div>
              </div>

              {/* Per-wall openings editor (defines geometry) */}
              <div className="space-y-2 border-t border-border pt-4">
                <RoomOpeningsEditor walls={walls} onChange={setWalls} disabled={isPending} />
              </div>

              {/* Derived metrics: read-only "Calculated" when geometry present,
                  editable "Manual" inputs otherwise. */}
              <div className="space-y-3 border-t border-border pt-4">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-medium text-foreground">
                    {t('rooms.form.metrics')}
                  </span>
                  <RoomSourceBadge source={hasGeometry ? 'CALCULATED' : 'MANUAL'} />
                </div>

                <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                  {metricFields.map((field) => {
                    const fieldError = errors[field.key]
                    return (
                      <div key={field.key} className="space-y-2">
                        <label
                          htmlFor={`room-${field.key}`}
                          className="text-sm font-medium text-foreground"
                        >
                          {field.label}
                        </label>
                        {hasGeometry ? (
                          <div
                            id={`room-${field.key}`}
                            data-testid={`room-${field.key}-calculated`}
                            className="flex h-10 w-full items-center justify-between rounded-md border border-dashed border-border bg-muted/40 px-3 text-sm text-muted-foreground"
                          >
                            <span>{field.calc ?? '—'}</span>
                            <span className="text-xs">{t('rooms.source.calculated')}</span>
                          </div>
                        ) : (
                          <>
                            <input
                              id={`room-${field.key}`}
                              type="number"
                              step="0.01"
                              min="0"
                              {...register(field.key)}
                              className={inputClass}
                            />
                            {fieldError && (
                              <p className="text-xs text-destructive">
                                {t(fieldError.message ?? '')}
                              </p>
                            )}
                          </>
                        )}
                      </div>
                    )
                  })}
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
                    ? t('rooms.form.submitCreate')
                    : t('rooms.form.submitEdit')}
              </button>
            </SheetFooter>
          </form>
        )}
      </SheetContent>
    </Sheet>
  )
}
