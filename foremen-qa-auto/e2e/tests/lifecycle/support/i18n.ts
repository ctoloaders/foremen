// UI strings the lifecycle suite matches on, per locale. The values are the texts in
// foremen-frontend/src/locales/{pl,ru}.json. Prefer testids where the app has them; only the
// text that cannot be addressed another way lives here.
//
// LIFECYCLE_LOCALE=ru runs the suite in Russian. The app reads the locale from localStorage
// ('foremen-locale'), which the suite sets before the first page loads (see lifecycle.e2e.ts).
export type Locale = 'pl' | 'ru';

export const LOCALE: Locale = process.env.LIFECYCLE_LOCALE === 'ru' ? 'ru' : 'pl';

const STRINGS = {
  pl: {
    password: 'Hasło',
    signIn: 'Zaloguj się',
    panelHeading: 'Panel',
    otpRequestCode: 'Wyślij kod',
    otpCodeTitle: 'Wprowadź kod',
    otpDigit: (i: number) => `Cyfra ${i} z 6`,
    navUsers: 'Użytkownicy',
    navRoles: 'Role',
    navAudit: 'Audyt',
    statusDraft: 'Szkic',
    statusActive: 'Aktywny',
    offerSent: 'Wysłana',
    completionText: /Ukończenie projektu: [1-9]/,
    composerLabel: 'Odpowiedź',
  },
  ru: {
    password: 'Пароль',
    signIn: 'Войти',
    panelHeading: 'Панель',
    otpRequestCode: 'Отправить код',
    otpCodeTitle: 'Введите код',
    otpDigit: (i: number) => `Цифра ${i} из 6`,
    navUsers: 'Пользователи',
    navRoles: 'Роли',
    navAudit: 'Аудит',
    statusDraft: 'Черновик',
    statusActive: 'Активный',
    offerSent: 'Отправлена',
    completionText: /Выполнение проекта: [1-9]/,
    composerLabel: 'Ответ',
  },
} as const;

export const T = STRINGS[LOCALE];

/** The localStorage value the app reads to pick its UI language. */
export const APP_LOCALE_INIT = `localStorage.setItem('foremen-locale', '${LOCALE}')`;
