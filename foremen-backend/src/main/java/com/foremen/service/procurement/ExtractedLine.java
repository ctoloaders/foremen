package com.foremen.service.procurement;

/**
 * FOR-06-03 (design §5.3, Requirements 5.1, 5.7) — the match-relevant projection of one extracted
 * procurement line fed to the pure {@code MatchEngine}.
 *
 * <ul>
 *   <li>{@link #rawName} — the verbatim OCR product text; the engine infers category containment and
 *       name containment from it (R5.2, R5.4).</li>
 *   <li>{@link #manufacturerHint} — the manufacturer the parse extracted, or {@code null}/blank when
 *       none; a blank hint cannot reach a manufacturer-bearing tier (R5.3).</li>
 * </ul>
 */
public record ExtractedLine(String rawName, String manufacturerHint) {
}
