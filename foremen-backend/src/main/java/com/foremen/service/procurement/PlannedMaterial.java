package com.foremen.service.procurement;

import java.util.List;

/**
 * FOR-06-03 (design §5.3, Requirements 4.1, 4.4) — one candidate the pure {@code MatchEngine}
 * matches an {@link ExtractedLine} against: a distinct chosen {@code Concrete_Material} from the
 * project's FOR-05-05b planned-material projection.
 *
 * <p>The three match dimensions are projected uniformly so a {@code ConstructionMaterial} and a
 * {@code FinishingMaterial} are compared the same way:
 * <ul>
 *   <li>{@link #materialId} — the soft FK the matched line stores (R1.5); also the final stable
 *       tie-break key (lowest id wins).</li>
 *   <li>{@link #manufacturer} — the material's {@code producer}.</li>
 *   <li>{@link #category} — the material {@code type}.</li>
 *   <li>{@link #names} — the localized names {@code [nameRU, namePL]} (the {@code ru → pl → code}
 *       fallback is applied by the candidate provider; any entry may be {@code null}/blank and is
 *       skipped during name matching).</li>
 * </ul>
 *
 * <p>This record is an immutable value with no I/O, so the engine stays pure and property-testable.
 */
public record PlannedMaterial(
        Long materialId,
        String manufacturer,
        String category,
        List<String> names) {
}
