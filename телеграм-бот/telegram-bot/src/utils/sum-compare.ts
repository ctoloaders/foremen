export interface SumComparisonResult {
  match: boolean;
}

/**
 * Compares user-entered sum with OCR-recognized gross_amount.
 * Returns match=true if:
 * - ocrAmount is null/undefined (no OCR amount to compare)
 * - |userAmount - ocrAmount| <= 0.01 (within rounding tolerance)
 */
export function compareSums(userAmount: number, ocrAmount: number | null | undefined): SumComparisonResult {
  if (ocrAmount === null || ocrAmount === undefined) {
    return { match: true };
  }
  // Round difference to 10 decimal places to avoid IEEE 754 floating-point artifacts
  // (e.g. Math.abs(100.00 - 100.01) === 0.010000000000005116 instead of 0.01)
  const diff = Math.round(Math.abs(userAmount - ocrAmount) * 1e10) / 1e10;
  return { match: diff <= 0.01 };
}
