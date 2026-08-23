import { describe, it, expect } from 'vitest'
import { handleResponse, ApiError } from '../api/roles-api'

function mockResponse(options: {
  ok: boolean
  status: number
  body?: unknown
  jsonFails?: boolean
}): Response {
  const { ok, status, body, jsonFails } = options
  return {
    ok,
    status,
    json: jsonFails
      ? () => Promise.reject(new Error('JSON parse error'))
      : () => Promise.resolve(body),
  } as unknown as Response
}

describe('handleResponse', () => {
  it('throws ApiError with status and message for non-ok responses', async () => {
    const response = mockResponse({
      ok: false,
      status: 404,
      body: { message: 'Not found' },
    })

    await expect(handleResponse(response)).rejects.toThrow(ApiError)
    await expect(handleResponse(mockResponse({ ok: false, status: 404, body: { message: 'Not found' } }))).rejects.toMatchObject({
      status: 404,
      message: 'Not found',
    })
  })

  it('parses JSON body for error message (message field)', async () => {
    const response = mockResponse({
      ok: false,
      status: 422,
      body: { message: 'Validation failed' },
    })

    try {
      await handleResponse(response)
      expect.fail('Should have thrown')
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError)
      expect((err as ApiError).status).toBe(422)
      expect((err as ApiError).message).toBe('Validation failed')
    }
  })

  it('parses JSON body for error field when message not present', async () => {
    const response = mockResponse({
      ok: false,
      status: 500,
      body: { error: 'Internal server error' },
    })

    try {
      await handleResponse(response)
      expect.fail('Should have thrown')
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError)
      expect((err as ApiError).status).toBe(500)
      expect((err as ApiError).message).toBe('Internal server error')
    }
  })

  it('handles responses where JSON parsing fails (returns generic message)', async () => {
    const response = mockResponse({
      ok: false,
      status: 503,
      jsonFails: true,
    })

    try {
      await handleResponse(response)
      expect.fail('Should have thrown')
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError)
      expect((err as ApiError).status).toBe(503)
      expect((err as ApiError).message).toBe('HTTP 503')
    }
  })

  it('returns parsed JSON for ok responses', async () => {
    const payload = { id: 1, name: 'Admin' }
    const response = mockResponse({
      ok: true,
      status: 200,
      body: payload,
    })

    const result = await handleResponse<{ id: number; name: string }>(response)
    expect(result).toEqual(payload)
  })
})
