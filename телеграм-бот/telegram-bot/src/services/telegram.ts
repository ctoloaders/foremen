import { Bot } from "grammy";

export async function downloadFile(
  bot: Bot,
  fileId: string
): Promise<{ buffer: Buffer; mimeType: string }> {
  const file = await bot.api.getFile(fileId);
  const filePath = file.file_path!;
  const url = `https://api.telegram.org/file/bot${bot.token}/${filePath}`;

  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`Failed to download file: ${response.status}`);
  }

  const arrayBuffer = await response.arrayBuffer();
  const buffer = Buffer.from(arrayBuffer);

  const mimeType = filePath.endsWith(".png") ? "image/png" : "image/jpeg";
  return { buffer, mimeType };
}
