import { z } from 'zod'

export const measurementUnitCreateSchema = z.object({
  code: z
    .string()
    .min(1, 'measurementUnits.validation.codeRequired')
    // Unit codes are short lowercase alphanumerics (m2, mb, szt, kpl, godz, ...).
    .regex(/^[a-z0-9]{1,50}$/, 'measurementUnits.validation.codePattern'),
  nameRU: z
    .string()
    .min(1, 'measurementUnits.validation.nameMin')
    .max(100, 'measurementUnits.validation.nameMax'),
  namePL: z
    .string()
    .min(1, 'measurementUnits.validation.nameMin')
    .max(100, 'measurementUnits.validation.nameMax'),
  active: z.boolean().default(true),
})

export const measurementUnitUpdateSchema = measurementUnitCreateSchema.omit({ code: true })

export type MeasurementUnitCreateFormValues = z.infer<typeof measurementUnitCreateSchema>
export type MeasurementUnitUpdateFormValues = z.infer<typeof measurementUnitUpdateSchema>
