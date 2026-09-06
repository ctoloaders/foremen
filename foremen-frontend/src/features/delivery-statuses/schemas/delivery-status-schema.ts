import { z } from 'zod'

export const deliveryStatusCreateSchema = z.object({
  code: z
    .string()
    .min(1, 'deliveryStatuses.validation.codeRequired')
    // Delivery status codes are lowercase snake_case identifiers (new, ordered, delivered, ...).
    .regex(/^[a-z0-9_]{1,50}$/, 'deliveryStatuses.validation.codePattern'),
  orderNo: z.coerce
    .number({ message: 'deliveryStatuses.validation.orderNoRequired' })
    .int('deliveryStatuses.validation.orderNoRequired')
    .min(0, 'deliveryStatuses.validation.orderNoRequired'),
  nameRU: z
    .string()
    .min(1, 'deliveryStatuses.validation.nameMin')
    .max(100, 'deliveryStatuses.validation.nameMax'),
  namePL: z
    .string()
    .min(1, 'deliveryStatuses.validation.nameMin')
    .max(100, 'deliveryStatuses.validation.nameMax'),
  active: z.boolean().default(true),
})

export const deliveryStatusUpdateSchema = deliveryStatusCreateSchema.omit({ code: true })

export type DeliveryStatusCreateFormValues = z.infer<typeof deliveryStatusCreateSchema>
export type DeliveryStatusUpdateFormValues = z.infer<typeof deliveryStatusUpdateSchema>
