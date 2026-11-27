package com.foremen.service.estimate;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * FOR-05-04 (design B11, Requirement 21.3): the per-cell AI marker and explanation record stored in
 * {@code estimate_line_room_qty.ai_meta} and copied into the offer snapshot cell. Staff-only: never
 * mapped into client read models.
 *
 * <p>Facts (formula volume, prices) come from the engine; texts ({@code rationale},
 * {@code adjustmentReason}, {@code price.reason}), {@code status}, {@code confidence} and
 * {@code source} come from the planner answer or the chat tool {@code reason}.
 *
 * @param origin     {@code JOB} or {@code CHAT}
 * @param turnId     chat turn id when {@code origin = CHAT}, otherwise {@code null}
 * @param status     {@code CONFIRMED} | {@code ASSUMED} | {@code QUESTION}
 * @param confidence model confidence, 0..1, nullable
 * @param source     where the model found the evidence, nullable
 * @param rationale  why this work applies to this room, nullable
 * @param volume     volume facts and correction, nullable
 * @param materials  summary of the seeded material lines, nullable
 * @param price      price facts and markup, nullable
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CellAiMeta(
        String origin,
        Long turnId,
        String status,
        BigDecimal confidence,
        Source source,
        String rationale,
        Volume volume,
        Materials materials,
        Price price) {

    /** Evidence location. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String file, Integer page) {
    }

    /**
     * @param source           {@code FORMULA} | {@code UNIT_FALLBACK} | {@code MODEL_OVERRIDE} | {@code CHAT_OVERRIDE}
     * @param formulaKey       the volume formula key the engine resolved, nullable
     * @param formulaVolume    the engine's formula volume (known even when overridden)
     * @param finalVolume      the resulting cell quantity
     * @param correction       difference between final and formula volume, nullable when not overridden
     * @param adjustmentReason explanation of the override, nullable
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Volume(
            String source,
            String formulaKey,
            BigDecimal formulaVolume,
            BigDecimal finalVolume,
            Correction correction,
            String adjustmentReason,
            String reasonCode) {
        /** Shape without the reason code ({@code MODEL_VOLUME_DIFFERS} when an override has no reason text). */
        public Volume(String source, String formulaKey, BigDecimal formulaVolume, BigDecimal finalVolume,
                      Correction correction, String adjustmentReason) {
            this(source, formulaKey, formulaVolume, finalVolume, correction, adjustmentReason, null);
        }
    }

    /** Volume correction: absolute and percent delta against the formula volume. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Correction(BigDecimal delta, BigDecimal pct) {
    }

    /** Material lines seeded from norms for the cell. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Materials(boolean seededFromNorms, int lines, int overridden) {
    }

    /** Price facts: base and effective unit price and the markup that explains the difference. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Price(
            BigDecimal basePrice,
            BigDecimal effectivePrice,
            BigDecimal pct,
            String scope,
            String reason) {
    }
}
