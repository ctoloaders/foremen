import { z } from 'zod'

export const roleCreateSchema = z.object({
  code: z
    .string()
    .min(1, 'roles.validation.codeRequired')
    .regex(/^[A-Z][A-Z0-9_]{1,49}$/, 'roles.validation.codePattern'),
  nameRU: z
    .string()
    .min(2, 'roles.validation.nameMin')
    .max(100, 'roles.validation.nameMax'),
  namePL: z
    .string()
    .min(2, 'roles.validation.nameMin')
    .max(100, 'roles.validation.nameMax'),
  descriptionRU: z.string().max(500, 'roles.validation.descriptionMax').optional().or(z.literal('')),
  descriptionPL: z.string().max(500, 'roles.validation.descriptionMax').optional().or(z.literal('')),
  system: z.boolean().default(false),
})

export const roleUpdateSchema = roleCreateSchema.omit({ code: true })

export type RoleCreateFormValues = z.infer<typeof roleCreateSchema>
export type RoleUpdateFormValues = z.infer<typeof roleUpdateSchema>
