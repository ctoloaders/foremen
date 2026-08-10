import { google } from "googleapis";
import { Readable } from "stream";
import { config } from "../config.js";
import { extractFolderId } from "../utils/validators.js";
import { transliterate } from "../utils/transliterate.js";

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

export async function uploadPhoto(
  driveUrl: string,
  storeName: string,
  sum: number,
  fileBuffer: Buffer,
  mimeType: string
): Promise<{ fileId: string; webViewLink: string }> {
  const drive = getDrive();
  const folderId = extractFolderId(driveUrl);

  // Generate filename: YYYY-MM-DD_HH-MM_store_sum.ext
  const now = new Date();
  const date = now.toISOString().split("T")[0];
  const time = `${String(now.getHours()).padStart(2, "0")}-${String(now.getMinutes()).padStart(2, "0")}`;
  const ext = mimeType === "image/png" ? "png" : "jpg";
  const fileName = `${date}_${time}_${transliterate(storeName)}_${sum}.${ext}`;

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
