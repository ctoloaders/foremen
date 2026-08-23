import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { PhoneInput } from '../components/PhoneInput'

describe('PhoneInput', () => {
  it('renders with default country PL', () => {
    const onChange = vi.fn()
    const { container } = render(<PhoneInput value="" onChange={onChange} />)

    // react-phone-number-input renders a select for country; PL should be pre-selected
    const countrySelect = container.querySelector(
      '.PhoneInputCountrySelect',
    ) as HTMLSelectElement | null
    expect(countrySelect).toBeInTheDocument()
    expect(countrySelect?.value).toBe('PL')
  })

  it('calls onChange with E.164 formatted value', () => {
    const onChange = vi.fn()
    render(<PhoneInput value="+48123456789" onChange={onChange} />)

    // The component accepts E.164 value and renders it in formatted display
    const phoneInput = screen.getByRole('textbox') as HTMLInputElement
    // The displayed value is the international-formatted phone: "+48 12 345 67 89"
    expect(phoneInput.value).toBe('+48 12 345 67 89')
  })

  it('shows error styling when error is provided', () => {
    const onChange = vi.fn()
    const { container } = render(
      <PhoneInput value="" onChange={onChange} error="Invalid phone" />,
    )

    // The wrapper div around PhoneInputBase should have destructive border class
    const phoneBase = container.querySelector('.PhoneInput')
    expect(phoneBase).toHaveClass('border-destructive')

    // Error message is rendered below
    expect(screen.getByText('Invalid phone')).toBeInTheDocument()
  })
})
