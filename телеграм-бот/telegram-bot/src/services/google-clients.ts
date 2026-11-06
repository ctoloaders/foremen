import { google } from "googleapis";
import { config } from "../config.js";

/**
 * Shared, lazily-created Google API clients.
 *
 * Previously every Sheets/Drive helper built a brand-new `GoogleAuth` on each call, which meant
 * every single API request first re-did the service-account JWT exchange (plus domain-wide
 * delegation when impersonating) to obtain an access token. Reusing one auth object lets
 * google-auth-library cache the token until it expires, which removes that extra round trip
 * (~200 ms per call measured) from the hot path.
 */

const SHEETS_SCOPES = ["https://www.googleapis.com/auth/spreadsheets"];
const DRIVE_SCOPES = ["https://www.googleapis.com/auth/drive"];

function createAuth(scopes: string[]) {
  const opts: any = {
    credentials: config.google.serviceAccountKey as any,
    scopes,
  };
  if (config.google.impersonateEmail) {
    opts.clientOptions = { subject: config.google.impersonateEmail };
  }
  return new google.auth.GoogleAuth(opts);
}

let sheetsClient: ReturnType<typeof google.sheets> | null = null;
let driveClient: ReturnType<typeof google.drive> | null = null;

/** Process-wide Sheets v4 client (token cached by the shared auth). */
export function getSheetsClient() {
  if (!sheetsClient) {
    sheetsClient = google.sheets({ version: "v4", auth: createAuth(SHEETS_SCOPES) });
  }
  return sheetsClient;
}

/** Process-wide Drive v3 client (token cached by the shared auth). */
export function getDriveClient() {
  if (!driveClient) {
    driveClient = google.drive({ version: "v3", auth: createAuth(DRIVE_SCOPES) });
  }
  return driveClient;
}
