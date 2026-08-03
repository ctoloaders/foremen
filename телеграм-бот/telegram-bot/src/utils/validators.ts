export function validateSum(input: string): number | null {
  const trimmed = input.trim().replace(",", ".");
  const match = trimmed.match(/^\d+(\.\d{1,2})?$/);
  if (!match) return null;
  const num = parseFloat(trimmed);
  if (num <= 0 || !isFinite(num)) return null;
  return num;
}

export function validateText(input: string, maxLength: number): string | null {
  const trimmed = input.trim();
  if (trimmed.length === 0 || trimmed.length > maxLength) return null;
  return trimmed;
}

export function isValidUrl(input: string): boolean {
  return input.startsWith("https://");
}

export function extractSpreadsheetId(url: string): string {
  const match = url.match(/\/spreadsheets\/d\/([a-zA-Z0-9_-]+)/);
  if (!match) throw new Error(`Cannot extract spreadsheet ID from URL: ${url}`);
  return match[1];
}

export function extractFolderId(url: string): string {
  const match = url.match(/\/folders\/([a-zA-Z0-9_-]+)/);
  if (!match) throw new Error(`Cannot extract folder ID from URL: ${url}`);
  return match[1];
}
