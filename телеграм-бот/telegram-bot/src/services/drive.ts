import { google } from "googleapis";
import { Readable } from "stream";
import { config } from "../config.js";
import { extractFolderId } from "../utils/validators.js";
import { transliterate } from "../utils/transliterate.js";

/**
 * Formats an array of photo links for insertion into a spreadsheet cell.
 * Single link: returns as-is.
 * Multiple links: joins with newline.
 */
export function formatPhotoLinks(links: string[]): string {
  return links.join("\n");
}

function getAuth() {
  const opts: any = {
    credentials: config.google.serviceAccountKey as any,
    scopes: ["https://www.googleapis.com/auth/drive"],
  };
  if (config.google.impersonateEmail) {
    opts.clientOptions = { subject: config.google.impersonateEmail };
  }
  return new google.auth.GoogleAuth(opts);
}

function getDrive() {
  return google.drive({ version: "v3", auth: getAuth() });
}

/**
 * Generates a filename for receipt photos uploaded to Drive.
 * Single page: YYYY-MM-DD_HH-MM_<store>_<sum>.<ext>
 * Multi-page:  YYYY-MM-DD_HH-MM_<store>_<sum>_page<N>.<ext>
 */
export function generateFileName(
  storeName: string,
  sum: number,
  mimeType: string,
  pageNum?: number,
): string {
  const now = new Date();
  const date = now.toISOString().split("T")[0];
  const time = `${String(now.getHours()).padStart(2, "0")}-${String(now.getMinutes()).padStart(2, "0")}`;
  const ext = mimeType === "image/png" ? "png" : "jpg";
  const store = transliterate(storeName);

  if (pageNum !== undefined) {
    return `${date}_${time}_${store}_${sum}_page${pageNum}.${ext}`;
  }
  return `${date}_${time}_${store}_${sum}.${ext}`;
}

/**
 * Internal helper: uploads a single file buffer to a Drive folder with a given filename.
 * Returns fileId and webViewLink.
 */
async function uploadFileToDrive(
  folderId: string,
  fileName: string,
  fileBuffer: Buffer,
  mimeType: string,
): Promise<{ fileId: string; webViewLink: string }> {
  const drive = getDrive();

  const stream = new Readable();
  stream.push(fileBuffer);
  stream.push(null);

  const res = await drive.files.create({
    requestBody: {
      name: fileName,
      parents: [folderId],
    },
    media: {
      mimeType,
      body: stream,
    },
    fields: "id,webViewLink",
  });

  const fileId = res.data.id!;

  // Make viewable by anyone with link
  await drive.permissions.create({
    fileId,
    requestBody: {
      type: "anyone",
      role: "reader",
    },
  });

  return {
    fileId,
    webViewLink: res.data.webViewLink || `https://drive.google.com/file/d/${fileId}/view`,
  };
}

export async function uploadPhoto(
  driveUrl: string,
  storeName: string,
  sum: number,
  fileBuffer: Buffer,
  mimeType: string,
): Promise<{ fileId: string; webViewLink: string }> {
  const folderId = extractFolderId(driveUrl);
  const fileName = generateFileName(storeName, sum, mimeType);
  return uploadFileToDrive(folderId, fileName, fileBuffer, mimeType);
}

/**
 * Generates a filename for the reprocessed receipt PDF uploaded to Drive.
 * Format: YYYY-MM-DD_HH-MM_<store>_<sum>.pdf
 */
export function generatePdfFileName(storeName: string, sum: number): string {
  const now = new Date();
  const date = now.toISOString().split("T")[0];
  const time = `${String(now.getHours()).padStart(2, "0")}-${String(now.getMinutes()).padStart(2, "0")}`;
  const store = transliterate(storeName);
  return `${date}_${time}_${store}_${sum}.pdf`;
}

/**
 * Uploads a single multi-page receipt PDF to the project's Drive folder.
 * Returns the webViewLink for the uploaded PDF.
 */
export async function uploadReceiptPdf(
  driveUrl: string,
  storeName: string,
  sum: number,
  pdfBuffer: Buffer,
): Promise<{ link: string }> {
  const folderId = extractFolderId(driveUrl);
  const fileName = generatePdfFileName(storeName, sum);
  const result = await uploadFileToDrive(folderId, fileName, pdfBuffer, "application/pdf");
  return { link: result.webViewLink };
}

/**
 * Uploads multiple photos (multi-page receipt) to a Drive folder.
 * For a single file: uses standard naming (no page suffix).
 * For multiple files: appends _page<N> to each filename.
 * Returns an array of webViewLinks for all uploaded photos.
 */
export async function uploadPhotos(
  driveUrl: string,
  storeName: string,
  sum: number,
  files: Array<{ buffer: Buffer; mimeType: string }>,
): Promise<{ links: string[] }> {
  const folderId = extractFolderId(driveUrl);
  const links: string[] = [];
  const isMultiPage = files.length > 1;

  for (let i = 0; i < files.length; i++) {
    const fileName = generateFileName(
      storeName,
      sum,
      files[i].mimeType,
      isMultiPage ? i + 1 : undefined,
    );
    const result = await uploadFileToDrive(folderId, fileName, files[i].buffer, files[i].mimeType);
    links.push(result.webViewLink);
  }

  return { links };
}
