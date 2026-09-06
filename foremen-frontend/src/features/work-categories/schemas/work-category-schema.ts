import { z } from 'zod'

export const workCategoryCreateSchema = z.object({
  code: z
    .string()
    .min(1, 'workCategories.validation.codeRequired')
    // Work category codes are UPPER_SNAKE_CASE identifiers (PRELIMINARY, PLUMBING_ROUGH, ...).
    .regex(/^[A-Z0-9_]{1,50}$/, 'workCategories.validation.codePattern'),
  orderNo: z.coerce
    .number({ message: 'workCategories.validation.orderNoRequired' })
    .int('workCategories.validation.orderNoRequired')
    .min(0, 'workCategories.validation.orderNoRequired'),
  nameRU: z
    .string()
    .min(1, 'workCategories.validation.nameMin')
    .max(100, 'workCategories.validation.nameMax'),
  namePL: z
    .string()
    .min(1, 'workCategories.validation.nameMin')
    .max(100, 'workCategories.validation.nameMax'),
  active: z.boolean().default(true),
})

export const workCategoryUpdateSchema = workCategoryCreateSchema.omit({ code: true })

export type WorkCategoryCreateFormValues = z.infer<typeof workCategoryCreateSchema>
export type WorkCategoryUpdateFormValues = z.infer<typeof workCategoryUpdateSchema>
