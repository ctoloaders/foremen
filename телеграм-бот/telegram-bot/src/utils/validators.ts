export function validateSum(input: string): number | null {
  // Trim and normalize separators
  let trimmed = input.trim();
  
  // Remove spaces and currency symbols
  trimmed = trimmed.replace(/[^\d.,]/g, "");
  
  // Handle both comma and dot as decimal separator
  // If both exist, last one is decimal (e.g., "1.055,45" or "1,055.45")
  const lastComma = trimmed.lastIndexOf(",");
  const lastDot = trimmed.lastIndexOf(".");
  
  if (lastComma > lastDot) {
    // Comma is decimal separator (European format: "55,45")
    trimmed = trimmed.replace(/\./g, "").replace(",", ".");
  } else if (lastDot > lastComma) {
    // Dot is decimal separator (US format: "55.45")
    trimmed = trimmed.replace(/,/g, "");
  } else {
    // Only one or none — replace comma with dot
    trimmed = trimmed.replace(",", ".");
  }

  // Validate: digits with optional decimal (max 2 places)
  const match = trimmed.match(/^\d+(\.\d{1,2})?$/);
  if (!match) return null;
  
  const num = parseFloat(trimmed);
  if (num <= 0 || !isFinite(num)) return null;
  return Math.round(num * 100) / 100; // ensure max 2 decimal places
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
