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
 * - an explicit "Enter areas manually" toggle (manualOverride) that lets the user
 *   override the geometry-derived metrics even while walls remain.
 *
 * Metric mode (FOR-04-bugs Bug 9 / Req 2.9, 3.4). The five derived metrics
 * (floorArea/wallArea/perimeter/doorArea/windowArea) are editable when the form is in
 * "manual" mode and read-only ("Calculated") otherwise. Manual mode is entered when
 * EITHER there are no walls (the pre-existing behavior) OR the user has explicitly
 * turned on the manual-override toggle. Crucially this is decoupled from `walls.length`:
 * previously `hasGeometry = walls.length > 0` meant a user who deleted one wall but left
 * others could never enter manual areas — they were silently discarded on submit. Now
 * the toggle lets manual entry win regardless of how many walls remain.
 *
 * Submit contract:
 * - Manual mode (no walls OR override on): geometry is sent as `null` and the
 *   user-entered manual metrics are submitted, so the backend persists them verbatim
 *   instead of recomputing from geometry.
 * - Pure-geometry mode (walls present, override off): geometry is sent and the manual
 *   metrics are forced to null (the backend recomputes) — the Req 3.4 path is unchanged.
 *
 * Validation runs via a zod resolver (onBlur + onSubmit). Submission is blocked on
 * any invalid field, entered values are retained, and a localized message is shown
 * under each invalid field.
 */
import { useEffect, useMemo, useState } from 'react'
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
import { NumberInput } from '@/components/ui/number-input'
import { useRoom } from '../api/query-hooks'
import { useCreateRoom, useUpdateRoom } from '../api/mutation-hooks'
import { roomCreateSchema, roomUpdateSchema } from '../schemas/room-schema'
import type { RoomCreateFormValues, RoomUpdateFormValues } from '../schemas/room-schema'
import type {
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

  const createMutation = useCreateRoom()
  const updateMutation = useUpdateRoom()

  const isCreate = mode === 'create'
  const schema = isCreate ? roomCreateSchema : roomUpdateSchema
  const isPending = createMutation.isPending || updateMutation.isPending

  // Per-wall openings state (source of the geometry payload).
  const [walls, setWalls] = useState<EditorWall[]>([])

  // Explicit manual-override flag (Bug 9 / Req 2.9): when ON the user is overriding the
  // geometry-derived metrics, so the metric fields are editable and their values are
  // submitted (geometry is dropped) even while walls remain. Decoupled from wall count.
  const [manualOverride, setManualOverride] = useState(false)

  // The metrics are entered/submitted manually when there are no walls (as before) OR
  // when the user has explicitly turned on the override. Otherwise we are in pure
  // geometry mode and the metrics are read-only "Calculated".
  const useManualMetrics = manualOverride || walls.length === 0

  const {
    register,
    handleSubmit,
    reset,
    control,
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
      const editWalls = geometryToWalls(roomData.geometry)
      setWalls(editWalls)
      // If the room was saved with no geometry but has manual metrics, open in manual
      // mode. When geometry exists the metrics are calculated, so override stays off
      // (the user can still turn it on to override). With no walls, `useManualMetrics`
      // is already true regardless of this flag.
      const hasStoredGeometry = editWalls.length > 0
      const hasManualMetrics =
        roomData.floorArea != null ||
        roomData.wallArea != null ||
        roomData.perimeter != null ||
        roomData.doorArea != null ||
        roomData.windowArea != null
      setManualOverride(!hasStoredGeometry && hasManualMetrics)
    } else if (mode === 'create' && open) {
      reset(emptyDefaults)
      setWalls([])
      setManualOverride(false)
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
    // Manual override (or no walls) means the user is supplying the metrics directly, so
    // we drop the geometry payload — sending it would make the backend recompute and
    // ignore the manual values (Bug 9 / Req 2.9). In pure-geometry mode we send geometry
    // and null the manual metrics so the backend recomputes them (Req 3.4, unchanged).
    const geometry = useManualMetrics ? null : wallsToGeometry(walls)
    const payload: RoomCreateRequest = {
      projectId: values.projectId,
      roomTypeId: values.roomTypeId,
      label: values.label ? values.label : null,
      ceilingHeight: values.ceilingHeight ?? null,
      internalCorners: values.internalCorners ?? null,
      geometry,
      // With geometry present (pure-geometry mode) the metrics are recomputed by the
      // backend, so they are nulled here; when geometry is null (manual mode) the
      // user-entered values are submitted verbatim.
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
              {/* Project selector — async search + infinite-scroll combobox
                  (Bug 7). The RHF value stays a number; `0` maps to the select's
                  "unselected" (null) so the existing min(1) validation and the
                  submit payload are unchanged. */}
              <div className="space-y-2">
                <label htmlFor="room-project" className="text-sm font-medium text-foreground">
                  {t('rooms.form.project')}
                </label>
                <Controller
                  control={control}
                  name="projectId"
                  render={({ field }) => (
                    <AsyncEntitySelect
                      id="room-project"
                      aria-label={t('rooms.form.project')}
                      optionsPath="/api/projects"
                      disabled={isPending}
                      placeholder={t('rooms.form.selectProject')}
                      value={field.value ? Number(field.value) : null}
                      onChange={(nextId) => field.onChange(nextId ?? 0)}
                    />
                  )}
                />
                {errors.projectId && (
                  <p className="text-xs text-destructive">{t(errors.projectId.message ?? '')}</p>
                )}
              </div>

              {/* Room-type selector — async combobox (Bug 7). */}
              <div className="space-y-2">
                <label htmlFor="room-type" className="text-sm font-medium text-foreground">
                  {t('rooms.form.roomType')}
                </label>
                <Controller
                  control={control}
                  name="roomTypeId"
                  render={({ field }) => (
                    <AsyncEntitySelect
                      id="room-type"
                      aria-label={t('rooms.form.roomType')}
                      optionsPath="/api/room-types"
                      disabled={isPending}
                      placeholder={t('rooms.form.selectRoomType')}
                      value={field.value ? Number(field.value) : null}
                      onChange={(nextId) => field.onChange(nextId ?? 0)}
                    />
                  )}
                />
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
                  {/* Decimal metric — accepts '.' or ',' (Bug 8). */}
                  <Controller
                    control={control}
                    name="ceilingHeight"
                    render={({ field }) => (
                      <NumberInput
                        id="room-ceiling"
                        value={field.value == null ? '' : String(field.value)}
                        onChange={field.onChange}
                        onBlur={field.onBlur}
                        className={inputClass}
                      />
                    )}
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

              {/* Derived metrics: editable "Manual" inputs in manual mode (no walls OR
                  override on), read-only "Calculated" otherwise. */}
              <div className="space-y-3 border-t border-border pt-4">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-medium text-foreground">
                    {t('rooms.form.metrics')}
                  </span>
                  <RoomSourceBadge source={useManualMetrics ? 'MANUAL' : 'CALCULATED'} />
                </div>

                {/* Manual-override toggle (Bug 9 / Req 2.9). Only meaningful when walls
                    exist — with no walls the metrics are already manual. Turning it on
                    lets the user override the geometry-derived metrics; the entered
                    values are then persisted instead of being recomputed. */}
                {walls.length > 0 && (
                  <label
                    htmlFor="room-manual-override"
                    className="flex items-center gap-2 text-sm text-foreground"
                  >
                    <input
                      id="room-manual-override"
                      type="checkbox"
                      checked={manualOverride}
                      disabled={isPending}
                      onChange={(e) => setManualOverride(e.target.checked)}
                      className="h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-ring"
                    />
                    {t('rooms.form.manualOverride')}
                  </label>
                )}

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
                        {!useManualMetrics ? (
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
                            {/* Decimal metric — accepts '.' or ',' (Bug 8). */}
                            <Controller
                              control={control}
                              name={field.key}
                              render={({ field: metricField }) => (
                                <NumberInput
                                  id={`room-${field.key}`}
                                  value={
                                    metricField.value == null
                                      ? ''
                                      : String(metricField.value)
                                  }
                                  onChange={metricField.onChange}
                                  onBlur={metricField.onBlur}
                                  className={inputClass}
                                />
                              )}
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
