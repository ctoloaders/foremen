/**
 * google-identity.ts — a thin, typed wrapper around Google Identity Services
 * (GIS) for the Foremen web client's Sign-in-with-Google flow (FOR-03-06,
 * Requirement 14).
 *
 * Responsibilities:
 *  - Lazily inject the GIS script (`https://accounts.google.com/gsi/client`)
 *    exactly once, memoizing the injection promise so concurrent callers share a
 *    single `<script>` load (Req 14.2).
 *  - Initialize `google.accounts.id` with `VITE_GOOGLE_CLIENT_ID` and drive the
 *    One Tap / credential prompt, resolving with the Google ID token (a JWT)
 *    returned in the credential callback (Req 14.2).
 *  - Reject/abort when the user cancels or dismisses the prompt (Req 14.10) and
 *    reject on script-load or initialization failures — i.e. any pre-token
 *    client failure (Req 14.11).
 *
 * The GIS runtime is loaded from a remote script rather than an npm package;
 * this module declares only the minimal slice of the `window.google.accounts.id`
 * surface it actually uses so it does not depend on an external `@types` package
 * that may not be installed.
 *
 * Public API (consumed by GoogleSignInButton, task 14.2):
 *  - {@link requestGoogleIdToken}: `() => Promise<string>` — resolves with the
 *    credential JWT, rejects with a {@link GoogleIdentityError} on
 *    cancel/dismiss or on script-load/init failure.
 *  - {@link GoogleIdentityError} / {@link GoogleIdentityErrorReason}: the typed
 *    error surface so callers can distinguish an aborted prompt from a genuine
 *    client failure.
 */

/** URL of the Google Identity Services client script. */
const GIS_SCRIPT_SRC = 'https://accounts.google.com/gsi/client'

// --- Minimal GIS type surface -----------------------------------------------
// Only the members this module uses are declared, to avoid depending on an
// external `@types/google.accounts` package that may not be present.

/** The credential response delivered to the GIS `callback`. */
interface GisCredentialResponse {
  /** The Google ID token (a JWT) proving the signed-in identity. */
  credential: string
  /** How the credential was selected (e.g. auto-select vs. user gesture). */
  select_by?: string
}

/** Configuration passed to `google.accounts.id.initialize`. */
interface GisIdConfiguration {
  client_id: string
  callback: (response: GisCredentialResponse) => void
  auto_select?: boolean
  cancel_on_tap_outside?: boolean
  use_fedcm_for_prompt?: boolean
}

/**
 * The notification object passed to the `google.accounts.id.prompt` listener,
 * describing why the prompt was not (or is no longer) displayed. Only the
 * accessors this module reads are declared.
 */
interface GisPromptMomentNotification {
  isNotDisplayed(): boolean
  isSkippedMoment(): boolean
  isDismissedMoment(): boolean
  getNotDisplayedReason(): string
  getSkippedReason(): string
  getDismissedReason(): string
}

/** The subset of `google.accounts.id` this module calls. */
interface GisIdApi {
  initialize(config: GisIdConfiguration): void
  prompt(listener?: (notification: GisPromptMomentNotification) => void): void
  cancel(): void
}

/** The subset of the injected `window.google` global this module reads. */
interface GisGlobal {
  accounts: {
    id: GisIdApi
  }
}

declare global {
  interface Window {
    google?: GisGlobal
  }
}

// --- Typed error surface -----------------------------------------------------

/**
 * Why a {@link requestGoogleIdToken} call failed:
 *  - `cancelled`: the user dismissed/cancelled the prompt, or it could not be
 *    displayed / was skipped — no token was obtained (Req 14.10).
 *  - `script_load_failed`: the GIS script could not be injected/loaded
 *    (Req 14.11).
 *  - `init_failed`: GIS loaded but initialization failed, or the client id is
 *    not configured (Req 14.11).
 */
export type GoogleIdentityErrorReason =
  | 'cancelled'
  | 'script_load_failed'
  | 'init_failed'

/**
 * The typed error rejected by {@link requestGoogleIdToken}. Its
 * {@link GoogleIdentityError.reason} lets the caller distinguish a
 * user-cancelled/dismissed prompt (return to idle, no error surfaced — Req
 * 14.10) from a genuine pre-token client failure (surface a generic
 * auth-failure message — Req 14.11).
 */
export class GoogleIdentityError extends Error {
  constructor(
    public readonly reason: GoogleIdentityErrorReason,
    message: string,
  ) {
    super(message)
    this.name = 'GoogleIdentityError'
  }

  /** True when the failure was a user cancel/dismiss rather than a client error (Req 14.10). */
  get isCancelled(): boolean {
    return this.reason === 'cancelled'
  }
}

// --- Script injection (memoized) --------------------------------------------

/**
 * The single, memoized GIS script-injection promise. The first call starts the
 * injection; every subsequent call reuses this promise so the `<script>` is
 * loaded only once (Req 14.2). Reset to `null` if injection fails so a later
 * call may retry a transient load failure.
 */
let scriptLoadPromise: Promise<void> | null = null

/**
 * Lazily injects the GIS client script exactly once and resolves when
 * `window.google.accounts.id` is available. Concurrent callers share the single
 * memoized promise. Rejects with a `script_load_failed`
 * {@link GoogleIdentityError} if the script cannot be loaded.
 */
function loadGisScript(): Promise<void> {
  if (window.google?.accounts?.id != null) {
    return Promise.resolve()
  }

  scriptLoadPromise ??= new Promise<void>((resolve, reject) => {
    const fail = (detail: string): void => {
      // Allow a later retry of a transient load failure.
      scriptLoadPromise = null
      reject(
        new GoogleIdentityError(
          'script_load_failed',
          `Failed to load Google Identity Services script: ${detail}`,
        ),
      )
    }

    const finish = (): void => {
      if (window.google?.accounts?.id != null) {
        resolve()
      } else {
        fail('script loaded but window.google.accounts.id is unavailable')
      }
    }

    // Reuse an already-present script tag if one exists (e.g. injected earlier).
    const existing = document.querySelector<HTMLScriptElement>(
      `script[src="${GIS_SCRIPT_SRC}"]`,
    )
    if (existing != null) {
      if (existing.dataset.loaded === 'true') {
        finish()
        return
      }
      existing.addEventListener('load', finish, { once: true })
      existing.addEventListener('error', () => fail('script error event'), {
        once: true,
      })
      return
    }

    const script = document.createElement('script')
    script.src = GIS_SCRIPT_SRC
    script.async = true
    script.defer = true
    script.addEventListener(
      'load',
      () => {
        script.dataset.loaded = 'true'
        finish()
      },
      { once: true },
    )
    script.addEventListener('error', () => fail('script error event'), {
      once: true,
    })
    document.head.appendChild(script)
  })

  return scriptLoadPromise
}

// --- Public API --------------------------------------------------------------

/** Guards against overlapping prompts (GIS allows only one at a time). */
let promptInFlight = false

/**
 * Reads and validates the configured Google OAuth client id from
 * `import.meta.env.VITE_GOOGLE_CLIENT_ID`. Throws an `init_failed`
 * {@link GoogleIdentityError} when it is missing/blank (Req 14.11).
 */
function getClientId(): string {
  const clientId = import.meta.env.VITE_GOOGLE_CLIENT_ID
  if (clientId == null || clientId.trim().length === 0) {
    throw new GoogleIdentityError(
      'init_failed',
      'VITE_GOOGLE_CLIENT_ID is not configured',
    )
  }
  return clientId
}

/**
 * Obtains a Google ID token (Google_ID_Token) via Google Identity Services.
 *
 * Lazily loads the GIS script (once), initializes `google.accounts.id` with the
 * configured `VITE_GOOGLE_CLIENT_ID`, and triggers the credential prompt. The
 * returned promise:
 *  - **resolves** with the credential JWT when the user completes sign-in
 *    (Req 14.2);
 *  - **rejects** with a `cancelled` {@link GoogleIdentityError} when the prompt
 *    is dismissed, skipped, or cannot be displayed — no token was obtained
 *    (Req 14.10);
 *  - **rejects** with a `script_load_failed` or `init_failed`
 *    {@link GoogleIdentityError} on any pre-token client failure (script load,
 *    missing client id, or initialization error — Req 14.11).
 *
 * @returns a promise resolving with the Google ID token (a JWT string).
 */
export async function requestGoogleIdToken(): Promise<string> {
  if (promptInFlight) {
    throw new GoogleIdentityError(
      'init_failed',
      'A Google sign-in prompt is already in progress',
    )
  }

  const clientId = getClientId()
  await loadGisScript()

  const idApi = window.google?.accounts?.id
  if (idApi == null) {
    throw new GoogleIdentityError(
      'init_failed',
      'Google Identity Services is unavailable after load',
    )
  }

  promptInFlight = true

  return new Promise<string>((resolve, reject) => {
    let settled = false

    const settleResolve = (token: string): void => {
      if (settled) return
      settled = true
      promptInFlight = false
      resolve(token)
    }

    const settleReject = (error: GoogleIdentityError): void => {
      if (settled) return
      settled = true
      promptInFlight = false
      reject(error)
    }

    try {
      idApi.initialize({
        client_id: clientId,
        callback: (response) => {
          if (
            response.credential != null &&
            response.credential.length > 0
          ) {
            settleResolve(response.credential)
          } else {
            settleReject(
              new GoogleIdentityError(
                'cancelled',
                'Google credential callback returned no credential',
              ),
            )
          }
        },
        cancel_on_tap_outside: true,
      })
    } catch (cause) {
      settleReject(
        new GoogleIdentityError(
          'init_failed',
          `Google Identity Services initialization failed: ${String(cause)}`,
        ),
      )
      return
    }

    try {
      idApi.prompt((notification) => {
        // The credential callback resolves the happy path. Here we only need to
        // detect the terminal "no credential will arrive" moments and reject as
        // a cancel/dismiss so the caller returns to idle (Req 14.10).
        if (
          notification.isNotDisplayed() ||
          notification.isSkippedMoment() ||
          notification.isDismissedMoment()
        ) {
          settleReject(
            new GoogleIdentityError(
              'cancelled',
              'Google sign-in prompt was dismissed or could not be displayed',
            ),
          )
        }
      })
    } catch (cause) {
      settleReject(
        new GoogleIdentityError(
          'init_failed',
          `Google Identity Services prompt failed: ${String(cause)}`,
        ),
      )
    }
  })
}
