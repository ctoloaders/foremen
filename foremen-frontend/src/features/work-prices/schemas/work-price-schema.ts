import { z } from 'zod'

export const workPriceCreateSchema = z.object({
  workItemId: z.coerce
    .number({ message: 'workPrices.validation.workItemRequired' })
    .int('workPrices.validation.workItemRequired')
    .min(1, 'workPrices.validation.workItemRequired'),
  currencyId: z.coerce
    .number({ message: 'workPrices.validation.currencyRequired' })
    .int('workPrices.validation.currencyRequired')
    .min(1, 'workPrices.validation.currencyRequired'),
  netPrice: z.coerce
    .number({ message: 'workPrices.validation.netPriceRequired' })
    .positive('workPrices.validation.netPricePositive'),
  validFrom: z
    .string()
    .min(1, 'workPrices.validation.validFromRequired'),
  validTo: z.string().optional().or(z.literal('')),
})

export const workPriceUpdateSchema = workPriceCreateSchema

export type WorkPriceCreateFormValues = z.infer<typeof workPriceCreateSchema>
export type WorkPriceUpdateFormValues = z.infer<typeof workPriceUpdateSchema>
