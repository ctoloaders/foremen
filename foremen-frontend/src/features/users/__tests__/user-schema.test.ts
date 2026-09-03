import { describe, it, expect } from 'vitest'
import { userFormSchema } from '../schemas/user-schema'

describe('userFormSchema', () => {
  const validPayload = {
    name: 'John Doe',
    email: 'john@example.com',
    phone: '+48789736625',
    roleId: 1,
    locale: 'pl' as const,
    active: true,
  }

  it('valid payload passes validation', () => {
    const result = userFormSchema.safeParse(validPayload)
    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data).toEqual(validPayload)
    }
  })

  describe('name validation', () => {
    it('name shorter than 2 chars fails', () => {
      const result = userFormSchema.safeParse({ ...validPayload, name: 'A' })
      expect(result.success).toBe(false)
      if (!result.success) {
        const nameErrors = result.error.issues.filter((i) => i.path.includes('name'))
        expect(nameErrors.length).toBeGreaterThan(0)
        expect(nameErrors[0]!.message).toBe('users.validation.nameMin')
      }
    })

    it('name longer than 100 chars fails', () => {
      const result = userFormSchema.safeParse({ ...validPayload, name: 'A'.repeat(101) })
      expect(result.success).toBe(false)
      if (!result.success) {
        const nameErrors = result.error.issues.filter((i) => i.path.includes('name'))
        expect(nameErrors.length).toBeGreaterThan(0)
        expect(nameErrors[0]!.message).toBe('users.validation.nameMax')
      }
    })

    it('name with exactly 2 chars passes', () => {
      const result = userFormSchema.safeParse({ ...validPayload, name: 'AB' })
      expect(result.success).toBe(true)
    })

    it('name with exactly 100 chars passes', () => {
      const result = userFormSchema.safeParse({ ...validPayload, name: 'A'.repeat(100) })
      expect(result.success).toBe(true)
    })
  })

  describe('email validation', () => {
    it('invalid email format is rejected', () => {
      const result = userFormSchema.safeParse({ ...validPayload, email: 'not-an-email' })
      expect(result.success).toBe(false)
      if (!result.success) {
        const emailErrors = result.error.issues.filter((i) => i.path.includes('email'))
        expect(emailErrors.length).toBeGreaterThan(0)
        expect(emailErrors[0]!.message).toBe('users.validation.emailInvalid')
      }
    })

    it('empty email fails with required error', () => {
      const result = userFormSchema.safeParse({ ...validPayload, email: '' })
      expect(result.success).toBe(false)
      if (!result.success) {
        const emailErrors = result.error.issues.filter((i) => i.path.includes('email'))
        expect(emailErrors.length).toBeGreaterThan(0)
      }
    })
  })

  describe('phone validation', () => {
    it('empty string passes (phone is optional)', () => {
      const result = userFormSchema.safeParse({ ...validPayload, phone: '' })
      expect(result.success).toBe(true)
    })

    it('undefined passes (phone is optional)', () => {
      const { phone, ...withoutPhone } = validPayload
      const result = userFormSchema.safeParse(withoutPhone)
      expect(result.success).toBe(true)
    })

    it('valid E.164 number passes', () => {
      const result = userFormSchema.safeParse({ ...validPayload, phone: '+48789736625' })
      expect(result.success).toBe(true)
    })

    it('invalid phone number is rejected', () => {
      const result = userFormSchema.safeParse({ ...validPayload, phone: '+123' })
      expect(result.success).toBe(false)
      if (!result.success) {
        const phoneErrors = result.error.issues.filter((i) => i.path.includes('phone'))
        expect(phoneErrors.length).toBeGreaterThan(0)
        expect(phoneErrors[0]!.message).toBe('users.validation.phoneInvalid')
      }
    })
  })

  describe('roleId validation', () => {
    it('undefined roleId fails', () => {
      const { roleId, ...withoutRole } = validPayload
      const result = userFormSchema.safeParse(withoutRole)
      expect(result.success).toBe(false)
      if (!result.success) {
        const roleErrors = result.error.issues.filter((i) => i.path.includes('roleId'))
        expect(roleErrors.length).toBeGreaterThan(0)
      }
    })

    it('roleId of 0 fails', () => {
      const result = userFormSchema.safeParse({ ...validPayload, roleId: 0 })
      expect(result.success).toBe(false)
      if (!result.success) {
        const roleErrors = result.error.issues.filter((i) => i.path.includes('roleId'))
        expect(roleErrors.length).toBeGreaterThan(0)
        expect(roleErrors[0]!.message).toBe('users.validation.roleRequired')
      }
    })
  })

  describe('locale validation', () => {
    it('valid locale "ru" passes', () => {
      const result = userFormSchema.safeParse({ ...validPayload, locale: 'ru' })
      expect(result.success).toBe(true)
    })

    it('valid locale "pl" passes', () => {
      const result = userFormSchema.safeParse({ ...validPayload, locale: 'pl' })
      expect(result.success).toBe(true)
    })

    it('locale "en" is rejected (English removed per BUG 1.5 fix)', () => {
      const result = userFormSchema.safeParse({ ...validPayload, locale: 'en' })
      expect(result.success).toBe(false)
      if (!result.success) {
        const localeErrors = result.error.issues.filter((i) => i.path.includes('locale'))
        expect(localeErrors.length).toBeGreaterThan(0)
      }
    })

    it('invalid locale is rejected', () => {
      const result = userFormSchema.safeParse({ ...validPayload, locale: 'de' })
      expect(result.success).toBe(false)
      if (!result.success) {
        const localeErrors = result.error.issues.filter((i) => i.path.includes('locale'))
        expect(localeErrors.length).toBeGreaterThan(0)
      }
    })
  })

  describe('active field', () => {
    it('defaults to true when omitted', () => {
      const { active, ...withoutActive } = validPayload
      const result = userFormSchema.safeParse(withoutActive)
      expect(result.success).toBe(true)
      if (result.success) {
        expect(result.data.active).toBe(true)
      }
    })

    it('explicit false is preserved', () => {
      const result = userFormSchema.safeParse({ ...validPayload, active: false })
      expect(result.success).toBe(true)
      if (result.success) {
        expect(result.data.active).toBe(false)
      }
    })
  })
})
