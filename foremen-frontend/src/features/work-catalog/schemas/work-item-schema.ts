import { z } from 'zod'

export const workItemCreateSchema = z.object({
  workCategoryId: z.coerce
    .number({ message: 'workCatalog.validation.workCategoryRequired' })
    .int('workCatalog.validation.workCategoryRequired')
    .min(1, 'workCatalog.validation.workCategoryRequired'),
  unitId: z.coerce
    .number({ message: 'workCatalog.validation.unitRequired' })
    .int('workCatalog.validation.unitRequired')
    .min(1, 'workCatalog.validation.unitRequired'),
  nameRU: z
    .string()
    .min(1, 'workCatalog.validation.nameMin')
    .max(255, 'workCatalog.validation.nameMax'),
  namePL: z
    .string()
    .min(1, 'workCatalog.validation.nameMin')
    .max(255, 'workCatalog.validation.nameMax'),
  active: z.boolean().default(true),
})

export const workItemUpdateSchema = workItemCreateSchema

export type WorkItemCreateFormValues = z.infer<typeof workItemCreateSchema>
export type WorkItemUpdateFormValues = z.infer<typeof workItemUpdateSchema>
