/// <reference types="vite/client" />

/**
 * Typed Vite environment variables exposed on `import.meta.env`.
 *
 * Extend {@link ImportMetaEnv} with each `VITE_`-prefixed variable the client
 * reads so `import.meta.env.VITE_*` accesses are strongly typed rather than
 * `any`.
 */
interface ImportMetaEnv {
  /**
   * The Google OAuth client id used by the Google Identity Services flow
   * (`src/lib/google-identity.ts`). Consumed by the Sign-in-with-Google control
   * to obtain a Google ID token that the backend exchanges for the app's JWT
   * pair (FOR-03-06, Requirement 14). May be undefined/blank when Google sign-in
   * is not configured for the current build.
   */
  readonly VITE_GOOGLE_CLIENT_ID?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
