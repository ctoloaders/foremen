package com.foremen.service.payment;

/**
 * Tranche-generation strategy for the {@link TrancheGenerator} (Requirement 2.1).
 *
 * <ul>
 *   <li>{@link #EQUAL} — split {@code basisGross} into N equal parts (remainder onto the last).</li>
 *   <li>{@link #PERCENT} — an ordered list of percentages that MUST sum to exactly {@code 100}.</li>
 *   <li>{@link #CUSTOM} — an ordered list of explicit amounts that MUST sum to exactly
 *       {@code basisGross}.</li>
 * </ul>
 */
public enum GeneratorStrategy {
    EQUAL,
    PERCENT,
    CUSTOM
}
