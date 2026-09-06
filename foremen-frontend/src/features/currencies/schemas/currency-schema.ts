import { z } from 'zod'

export const currencyCreateSchema = z.object({
  code: z
    .string()
    .min(1, 'currencies.validation.codeRequired')
    // Currency codes are ISO-4217-ish letter codes (PLN, EUR, USD, ...).
    .regex(/^[A-Za-z]{2,10}$/, 'currencies.validation.codePattern'),
  symbol: z.string().min(1, 'currencies.validation.symbolRequired'),
  nameRU: z
    .string()
    .min(1, 'currencies.validation.nameMin')
    .max(100, 'currencies.validation.nameMax'),
  namePL: z
    .string()
    .min(1, 'currencies.validation.nameMin')
    .max(100, 'currencies.validation.nameMax'),
  active: z.boolean().default(true),
})

export const currencyUpdateSchema = currencyCreateSchema.omit({ code: true })

export type CurrencyCreateFormValues = z.infer<typeof currencyCreateSchema>
export type CurrencyUpdateFormValues = z.infer<typeof currencyUpdateSchema>
