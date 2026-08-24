/**
 * Determines the sum to save in the spreadsheet.
 * Property 8: Always uses user-entered sum, regardless of OCR amount.
 *
 * In both OCR and manual flows, the user's entered sum is the authoritative
 * value stored in the spreadsheet. The OCR gross_amount is advisory only
 * (used for mismatch warnings but never persisted as the final sum).
 */
export function getSumToSave(userEnteredSum: number, _ocrGrossAmount: number | null | undefined): number {
  return userEnteredSum;
}
