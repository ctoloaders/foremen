import { z } from 'zod'
import { isValidPhoneNumber } from 'libphonenumber-js'

const phoneSchema = z
  .string()
  .optional()
  .or(z.literal(''))
  .refine(
    (val) => !val || val === '' || isValidPhoneNumber(val),
    'users.validation.phoneInvalid'
  )

export const userFormSchema = z.object({
  name: z
    .string()
    .min(2, 'users.validation.nameMin')
    .max(100, 'users.validation.nameMax'),
  email: z
    .string()
    .min(1, 'users.validation.emailRequired')
    .email('users.validation.emailInvalid'),
  phone: phoneSchema,
  roleId: z
    .number({ required_error: 'users.validation.roleRequired' })
    .min(1, 'users.validation.roleRequired'),
  locale: z.enum(['ru', 'pl'], {
    required_error: 'users.validation.localeRequired',
  }),
  active: z.boolean().default(true),
})

export type UserFormValues = z.infer<typeof userFormSchema>
