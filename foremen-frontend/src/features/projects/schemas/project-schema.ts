import { z } from 'zod'
import { PROJECT_STATUSES } from '../types'

/**
 * Base project fields shared by the create and update forms (FOR-04-13 Requirements 8.4, 8.8).
 * Mirrors the backend base-field validation:
 * - `name` non-blank, 1..255 characters (after trimming).
 * - `area` optional; when present must be within 0.01 .. 999999999.99.
 * - `status` one of the five defined {@link ProjectStatus} values.
 * - `startDate`/`endDate` optional ISO date strings.
 *
 * The `endDate >= startDate` rule is enforced by a cross-field refinement below (Requirement 8.8),
 * surfacing the error on the `endDate` field. Validation messages are i18n keys under `projects.*`,
 * resolved by the form's error renderer.
 */
const projectBaseShape = {
  name: z
    .string()
    .trim()
    .min(1, 'projects.validation.nameRequired')
    .max(255, 'projects.validation.nameMax'),
  address: z.string().max(500, 'projects.validation.addressMax').optional().or(z.literal('')),
  googlePlaceId: z.string().optional().or(z.literal('')),
  formattedAddress: z
    .string()
    .max(500, 'projects.validation.formattedAddressMax')
    .optional()
    .or(z.literal('')),
  latitude: z.number().nullable().optional(),
  longitude: z.number().nullable().optional(),
  area: z.coerce
    .number({ message: 'projects.validation.areaInvalid' })
    .min(0.01, 'projects.validation.areaRange')
    .max(999999999.99, 'projects.validation.areaRange')
    .nullable()
    .optional(),
  startDate: z.string().optional().or(z.literal('')),
  endDate: z.string().optional().or(z.literal('')),
  status: z.enum(PROJECT_STATUSES as unknown as [string, ...string[]], {
    message: 'projects.validation.statusInvalid',
  }),
}

/**
 * Cross-field refinement enforcing that `endDate` is on or after `startDate` when both are present
 * (Requirement 8.8). Attaches the error to the `endDate` field so the form highlights it.
 */
const endAfterStart = (
  data: { startDate?: string; endDate?: string },
  ctx: z.RefinementCtx,
): void => {
  if (data.startDate && data.endDate && data.endDate < data.startDate) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      path: ['endDate'],
      message: 'projects.validation.endBeforeStart',
    })
  }
}

/**
 * Create form schema: base fields + a `client` block is handled outside zod (the ClientBlock and
 * team selector manage their own state). The team `members` selection is validated by the form, not
 * here, because it is a UI-managed multi-select. This schema validates the base + date-range rule.
 */
export const projectCreateSchema = z.object(projectBaseShape).superRefine(endAfterStart)

/** Update form schema: identical base-field + date-range validation (base fields only, Req 3.3). */
export const projectUpdateSchema = z.object(projectBaseShape).superRefine(endAfterStart)

export type ProjectCreateFormValues = z.infer<typeof projectCreateSchema>
export type ProjectUpdateFormValues = z.infer<typeof projectUpdateSchema>
