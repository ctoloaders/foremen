import { z } from 'zod'

export const vatRateCreateSchema = z.object({
  code: z
    .string()
    .min(1, 'vatRates.validation.codeRequired')
    // VAT rate codes are short alphanumeric identifiers (23, 8, 5, 0, ...).
    .regex(/^[A-Za-z0-9]{1,20}$/, 'vatRates.validation.codePattern'),
  rate: z.coerce
    .number({ message: 'vatRates.validation.rateRequired' })
    .min(0, 'vatRates.validation.rateRequired'),
  nameRU: z
    .string()
    .min(1, 'vatRates.validation.nameMin')
    .max(100, 'vatRates.validation.nameMax'),
  namePL: z
    .string()
    .min(1, 'vatRates.validation.nameMin')
    .max(100, 'vatRates.validation.nameMax'),
  isDefault: z.boolean().default(false),
  active: z.boolean().default(true),
})

export const vatRateUpdateSchema = vatRateCreateSchema.omit({ code: true })

export type VatRateCreateFormValues = z.infer<typeof vatRateCreateSchema>
export type VatRateUpdateFormValues = z.infer<typeof vatRateUpdateSchema>
