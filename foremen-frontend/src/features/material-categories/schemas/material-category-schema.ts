import { z } from 'zod'

export const materialCategoryCreateSchema = z.object({
  code: z
    .string()
    .min(1, 'materialCategories.validation.codeRequired')
    // Material-category codes are short lowercase alphanumerics with optional underscores
    // (construction, finishing, ...).
    .regex(/^[a-z0-9_]{1,50}$/, 'materialCategories.validation.codePattern'),
  nameRU: z
    .string()
    .min(1, 'materialCategories.validation.nameMin')
    .max(100, 'materialCategories.validation.nameMax'),
  namePL: z
    .string()
    .min(1, 'materialCategories.validation.nameMin')
    .max(100, 'materialCategories.validation.nameMax'),
  active: z.boolean().default(true),
})

export const materialCategoryUpdateSchema = materialCategoryCreateSchema.omit({ code: true })

export type MaterialCategoryCreateFormValues = z.infer<typeof materialCategoryCreateSchema>
export type MaterialCategoryUpdateFormValues = z.infer<typeof materialCategoryUpdateSchema>
