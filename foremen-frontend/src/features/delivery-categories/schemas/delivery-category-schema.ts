import { z } from 'zod'

export const deliveryCategoryCreateSchema = z.object({
  code: z
    .string()
    .min(1, 'deliveryCategories.validation.codeRequired')
    // Delivery-category codes are short lowercase alphanumerics with optional underscores
    // (tiles, paints, bathroom_equipment, ...).
    .regex(/^[a-z0-9_]{1,50}$/, 'deliveryCategories.validation.codePattern'),
  nameRU: z
    .string()
    .min(1, 'deliveryCategories.validation.nameMin')
    .max(100, 'deliveryCategories.validation.nameMax'),
  namePL: z
    .string()
    .min(1, 'deliveryCategories.validation.nameMin')
    .max(100, 'deliveryCategories.validation.nameMax'),
  active: z.boolean().default(true),
})

export const deliveryCategoryUpdateSchema = deliveryCategoryCreateSchema.omit({ code: true })

export type DeliveryCategoryCreateFormValues = z.infer<typeof deliveryCategoryCreateSchema>
export type DeliveryCategoryUpdateFormValues = z.infer<typeof deliveryCategoryUpdateSchema>
