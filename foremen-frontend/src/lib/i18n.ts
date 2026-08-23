import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import pl from '@/locales/pl.json'
import ru from '@/locales/ru.json'

function getInitialLocale(): 'pl' | 'ru' {
  try {
    const stored = localStorage.getItem('foremen-locale')
    if (stored === 'pl' || stored === 'ru') return stored
  } catch {
    // localStorage unavailable
  }
  return 'pl'
}

i18n.use(initReactI18next).init({
  resources: {
    pl: { translation: pl },
    ru: { translation: ru },
  },
  lng: getInitialLocale(),
  fallbackLng: 'pl',
  interpolation: {
    escapeValue: false,
  },
  parseMissingKeyHandler: (key) => key,
})

export default i18n
