import { z } from 'zod'

export const roomTypeCreateSchema = z.object({
  code: z
    .string()
    .min(1, 'roomTypes.validation.codeRequired')
    // Room-type codes are short lowercase alphanumerics (kuchnia, salon, hol, ...).
    .regex(/^[a-z0-9]{1,50}$/, 'roomTypes.validation.codePattern'),
  nameRU: z
    .string()
    .min(1, 'roomTypes.validation.nameMin')
    .max(100, 'roomTypes.validation.nameMax'),
  namePL: z
    .string()
    .min(1, 'roomTypes.validation.nameMin')
    .max(100, 'roomTypes.validation.nameMax'),
  active: z.boolean().default(true),
})

export const roomTypeUpdateSchema = roomTypeCreateSchema.omit({ code: true })

export type RoomTypeCreateFormValues = z.infer<typeof roomTypeCreateSchema>
export type RoomTypeUpdateFormValues = z.infer<typeof roomTypeUpdateSchema>
