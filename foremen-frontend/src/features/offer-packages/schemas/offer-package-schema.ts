import { z } from 'zod'

export const offerPackageCreateSchema = z.object({
  code: z
    .string()
    .min(1, 'offerPackages.validation.codeRequired')
    // Offer package codes are lowercase snake_case identifiers (budget, norm, lux, ...).
    .regex(/^[a-z0-9_]{1,50}$/, 'offerPackages.validation.codePattern'),
  orderNo: z.coerce
    .number({ message: 'offerPackages.validation.orderNoRequired' })
    .int('offerPackages.validation.orderNoRequired')
    .min(0, 'offerPackages.validation.orderNoRequired'),
  nameRU: z
    .string()
    .min(1, 'offerPackages.validation.nameMin')
    .max(100, 'offerPackages.validation.nameMax'),
  namePL: z
    .string()
    .min(1, 'offerPackages.validation.nameMin')
    .max(100, 'offerPackages.validation.nameMax'),
  active: z.boolean().default(true),
})

export const offerPackageUpdateSchema = offerPackageCreateSchema.omit({ code: true })

export type OfferPackageCreateFormValues = z.infer<typeof offerPackageCreateSchema>
export type OfferPackageUpdateFormValues = z.infer<typeof offerPackageUpdateSchema>
