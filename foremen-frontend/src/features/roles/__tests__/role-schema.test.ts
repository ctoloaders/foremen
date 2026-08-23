import { describe, it, expect } from 'vitest'
import { roleCreateSchema, roleUpdateSchema } from '../schemas/role-schema'

describe('roleCreateSchema', () => {
  const validPayload = {
    code: 'ADMIN_ROLE',
    nameRU: 'Администратор',
    namePL: 'Administrator',
    descriptionRU: 'Полный доступ',
    descriptionPL: 'Pełny dostęp',
    system: true,
  }

  it('valid create payload passes validation', () => {
    const result = roleCreateSchema.safeParse(validPayload)
    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data).toEqual(validPayload)
    }
  })

  it('empty code fails with codeRequired error', () => {
    const result = roleCreateSchema.safeParse({ ...validPayload, code: '' })
    expect(result.success).toBe(false)
    if (!result.success) {
      const codeErrors = result.error.issues.filter((i) => i.path.includes('code'))
      expect(codeErrors.length).toBeGreaterThan(0)
      expect(codeErrors[0]!.message).toBe('roles.validation.codeRequired')
    }
  })

  it('code with lowercase letters fails with codePattern error', () => {
    const result = roleCreateSchema.safeParse({ ...validPayload, code: 'admin_role' })
    expect(result.success).toBe(false)
    if (!result.success) {
      const codeErrors = result.error.issues.filter((i) => i.path.includes('code'))
      expect(codeErrors.some((e) => e.message === 'roles.validation.codePattern')).toBe(true)
    }
  })

  it('code starting with a digit fails', () => {
    const result = roleCreateSchema.safeParse({ ...validPayload, code: '1ADMIN' })
    expect(result.success).toBe(false)
    if (!result.success) {
      const codeErrors = result.error.issues.filter((i) => i.path.includes('code'))
      expect(codeErrors.some((e) => e.message === 'roles.validation.codePattern')).toBe(true)
    }
  })

  it('nameRU shorter than 2 chars fails', () => {
    const result = roleCreateSchema.safeParse({ ...validPayload, nameRU: 'A' })
    expect(result.success).toBe(false)
    if (!result.success) {
      const nameErrors = result.error.issues.filter((i) => i.path.includes('nameRU'))
      expect(nameErrors.length).toBeGreaterThan(0)
      expect(nameErrors[0]!.message).toBe('roles.validation.nameMin')
    }
  })

  it('namePL longer than 100 chars fails', () => {
    const result = roleCreateSchema.safeParse({ ...validPayload, namePL: 'A'.repeat(101) })
    expect(result.success).toBe(false)
    if (!result.success) {
      const nameErrors = result.error.issues.filter((i) => i.path.includes('namePL'))
      expect(nameErrors.length).toBeGreaterThan(0)
      expect(nameErrors[0]!.message).toBe('roles.validation.nameMax')
    }
  })

  it('description longer than 500 chars fails', () => {
    const result = roleCreateSchema.safeParse({
      ...validPayload,
      descriptionRU: 'X'.repeat(501),
    })
    expect(result.success).toBe(false)
    if (!result.success) {
      const descErrors = result.error.issues.filter((i) => i.path.includes('descriptionRU'))
      expect(descErrors.length).toBeGreaterThan(0)
      expect(descErrors[0]!.message).toBe('roles.validation.descriptionMax')
    }
  })

  it('system defaults to false when omitted', () => {
    const { system, ...withoutSystem } = validPayload
    const result = roleCreateSchema.safeParse(withoutSystem)
    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data.system).toBe(false)
    }
  })
})

describe('roleUpdateSchema', () => {
  it('does not include code field', () => {
    const result = roleUpdateSchema.safeParse({
      nameRU: 'Администратор',
      namePL: 'Administrator',
    })
    expect(result.success).toBe(true)
    if (result.success) {
      expect('code' in result.data).toBe(false)
    }
  })
})
