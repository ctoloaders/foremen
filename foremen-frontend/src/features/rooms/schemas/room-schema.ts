import { z } from 'zod'

const MAX_METRIC = 9_999_999_999.99

/** A per-wall opening entry. type/count/height/width must all be positive. */
export const openingSchema = z.object({
  type: z.enum(['DOOR', 'WINDOW'], {
    message: 'rooms.validation.openingTypeRequired',
  }),
  count: z.coerce
    .number({ message: 'rooms.validation.openingCountPositive' })
    .int('rooms.validation.openingCountPositive')
    .positive('rooms.validation.openingCountPositive'),
  height: z.coerce
    .number({ message: 'rooms.validation.openingHeightPositive' })
    .positive('rooms.validation.openingHeightPositive'),
  width: z.coerce
    .number({ message: 'rooms.validation.openingWidthPositive' })
    .positive('rooms.validation.openingWidthPositive'),
})

export const wallSchema = z.object({
  wallGap: z.coerce.number().nonnegative('rooms.validation.metricRange').optional().nullable(),
  finishGap: z.coerce.number().nonnegative('rooms.validation.metricRange').optional().nullable(),
  openings: z.array(openingSchema),
})

export const vertexSchema = z.object({
  x: z.coerce.number(),
  y: z.coerce.number(),
})

/** Geometry is optional; when present its polygon must have >= 3 vertices. */
export const geometrySchema = z
  .object({
    vertices: z.array(vertexSchema).min(3, 'rooms.validation.geometryVertices'),
    walls: z.array(wallSchema),
  })
  .optional()
  .nullable()

const optionalMetric = z.coerce
  .number({ message: 'rooms.validation.metricRange' })
  .min(0, 'rooms.validation.metricRange')
  .max(MAX_METRIC, 'rooms.validation.metricRange')
  .optional()
  .nullable()

export const roomCreateSchema = z.object({
  projectId: z.coerce
    .number({ message: 'rooms.validation.projectRequired' })
    .int('rooms.validation.projectRequired')
    .min(1, 'rooms.validation.projectRequired'),
  roomTypeId: z.coerce
    .number({ message: 'rooms.validation.roomTypeRequired' })
    .int('rooms.validation.roomTypeRequired')
    .min(1, 'rooms.validation.roomTypeRequired'),
  label: z.string().max(255, 'rooms.validation.labelTooLong').optional().nullable(),
  ceilingHeight: optionalMetric,
  internalCorners: z.coerce
    .number({ message: 'rooms.validation.countNonNegative' })
    .int('rooms.validation.countNonNegative')
    .min(0, 'rooms.validation.countNonNegative')
    .optional()
    .nullable(),
  geometry: geometrySchema,
  // Manual metrics — ignored server-side when geometry is present.
  floorArea: optionalMetric,
  wallArea: optionalMetric,
  perimeter: optionalMetric,
  doorArea: optionalMetric,
  windowArea: optionalMetric,
})

export const roomUpdateSchema = roomCreateSchema

export type OpeningFormValues = z.infer<typeof openingSchema>
export type WallFormValues = z.infer<typeof wallSchema>
export type RoomCreateFormValues = z.infer<typeof roomCreateSchema>
export type RoomUpdateFormValues = z.infer<typeof roomUpdateSchema>
