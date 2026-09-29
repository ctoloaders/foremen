package com.foremen.service.formula;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkPackageOverrideEntity;
import com.foremen.dao.model.WorkVolumeFormulaEntity;
import com.foremen.dao.model.formula.FormulaAst;
import com.foremen.dao.model.formula.FormulaAst.WorkRef;

/**
 * The pure Volume-resolution core for a single work in a single room (FOR-05-05, Requirement 5, and
 * the design's Component B5). Composes the shipped formula precedence and evaluator with the
 * {@link VolumeFallbackResolver} unit&rarr;dimension fallback:
 *
 * <ol>
 *   <li>{@code applicable = }{@link FormulaRoomQtyDeriver#resolveApplicableFormula(WorkPackageOverrideEntity,
 *       WorkVolumeFormulaEntity)} &mdash; override AST &rarr; default AST &rarr; {@code null}
 *       (R3.2, R1.4);</li>
 *   <li>if a formula applies, {@code Volume = }{@link FormulaEvaluator#evaluate} against the room's
 *       14 dimensions (missing dimension &rarr; {@code 0}, per the evaluator's contract, R1.4) and
 *       the caller-supplied cross-work volumes (R3.3);</li>
 *   <li>if no formula applies, {@code Volume = }{@link VolumeFallbackResolver#resolve} for the
 *       work's unit code (R5.1&ndash;R5.4). The result flags that the fallback was used so the
 *       {@code Cell_Report} can disclose it (R5.3).</li>
 * </ol>
 *
 * <p>Pure, total and deterministic: no I/O, no state, no persistence; the same inputs always yield
 * an equal {@link Resolution}. The DB-aware caller fetches the {@link WorkPackageOverrideEntity} for
 * {@code (workItem, activePackage)}, the work's default {@link WorkVolumeFormulaEntity}, the work's
 * unit code, and the map of already-resolved cross-work volumes, then hands them here &mdash;
 * mirroring the {@link FormulaRoomQtyDeriver} DB/pure split.
 */
public final class VolumeResolver {

    private VolumeResolver() {
    }

    /**
     * How a cell's Volume was resolved.
     *
     * @param volume       the resolved Volume, never {@code null} (the fallback and the evaluator
     *                     both return {@link BigDecimal#ZERO} rather than {@code null})
     * @param source       whether the value came from an applicable formula or the unit fallback
     * @param fallbackUsed convenience flag, {@code true} iff {@code source == }{@link Source#FALLBACK}
     *                     &mdash; surfaced for the R5.3 disclosure
     */
    public record Resolution(BigDecimal volume, Source source, boolean fallbackUsed) {

        static Resolution ofFormula(BigDecimal volume) {
            return new Resolution(volume, Source.FORMULA, false);
        }

        static Resolution ofFallback(BigDecimal volume) {
            return new Resolution(volume, Source.FALLBACK, true);
        }
    }

    /** Whether a Volume was computed from a formula or from the unit&rarr;dimension fallback. */
    public enum Source {
        /** An applicable formula (override or default) was evaluated against the room dimensions. */
        FORMULA,
        /** No formula applied; the {@link VolumeFallbackResolver} unit fallback supplied the value. */
        FALLBACK
    }

    /**
     * Resolves the Volume for a work in a room, with no cross-work references (the common case).
     * Equivalent to {@link #resolve(WorkPackageOverrideEntity, WorkVolumeFormulaEntity, String,
     * RoomEntity, Function)} with a resolver that treats every {@link WorkRef} as zero.
     *
     * @param override       the {@code (workItem, activePackage)} override row, or {@code null}
     * @param defaultFormula the work's default volume formula row, or {@code null}
     * @param unitCode       the work's measurement unit code (drives the fallback), may be {@code null}
     * @param room           the room whose 14 dimensions the formula/fallback consume
     * @return the {@link Resolution}
     */
    public static Resolution resolve(WorkPackageOverrideEntity override,
                                     WorkVolumeFormulaEntity defaultFormula,
                                     String unitCode,
                                     RoomEntity room) {
        return resolve(override, defaultFormula, unitCode, room, ref -> BigDecimal.ZERO);
    }

    /**
     * Resolves the Volume for a work in a room, following override &rarr; default &rarr; unit
     * fallback precedence.
     *
     * @param override         the {@code (workItem, activePackage)} override row, or {@code null}
     * @param defaultFormula   the work's default volume formula row, or {@code null}
     * @param unitCode         the work's measurement unit code (drives the fallback), may be {@code null}
     * @param room             the room whose 14 dimensions the formula/fallback consume
     * @param resolvedVolumeOf resolves a {@link WorkRef} to that work's already-resolved Volume for
     *                         the same room (R3.3); an absent work should resolve to zero
     * @return the {@link Resolution}
     * @throws com.foremen.exception.ForemenApiException {@code 400 error.formula.division.by.zero}
     *                              if an applicable formula divides by zero during evaluation
     */
    public static Resolution resolve(WorkPackageOverrideEntity override,
                                     WorkVolumeFormulaEntity defaultFormula,
                                     String unitCode,
                                     RoomEntity room,
                                     Function<WorkRef, BigDecimal> resolvedVolumeOf) {
        FormulaAst applicable =
                FormulaRoomQtyDeriver.resolveApplicableFormula(override, defaultFormula);

        if (applicable != null) {
            BigDecimal volume = FormulaEvaluator.evaluate(applicable, roomVariables(room), resolvedVolumeOf);
            return Resolution.ofFormula(volume);
        }

        return Resolution.ofFallback(VolumeFallbackResolver.resolve(unitCode, room));
    }

    /**
     * Builds the room-dimension variable map from the 14 dimension columns, omitting {@code null}
     * columns so {@link FormulaEvaluator}'s own missing-variable-is-zero contract remains the single
     * source of that behaviour (identical to {@link FormulaRoomQtyDeriver}'s private binding).
     * Integer columns ({@code doorCount}, {@code internalCorners}) become {@link BigDecimal}s. A
     * {@code null} room yields an empty map (every variable then resolves to zero).
     */
    private static Map<String, BigDecimal> roomVariables(RoomEntity room) {
        Map<String, BigDecimal> vars = new HashMap<>();
        if (room == null) {
            return vars;
        }
        putIfPresent(vars, "floorArea", room.getFloorArea());
        putIfPresent(vars, "wallArea", room.getWallArea());
        putIfPresent(vars, "perimeter", room.getPerimeter());
        putIfPresent(vars, "doorCount", room.getDoorCount());
        putIfPresent(vars, "doorHeight", room.getDoorHeight());
        putIfPresent(vars, "doorWidth", room.getDoorWidth());
        putIfPresent(vars, "doorArea", room.getDoorArea());
        putIfPresent(vars, "wallGap", room.getWallGap());
        putIfPresent(vars, "finishGap", room.getFinishGap());
        putIfPresent(vars, "windowHeight", room.getWindowHeight());
        putIfPresent(vars, "windowWidth", room.getWindowWidth());
        putIfPresent(vars, "windowArea", room.getWindowArea());
        putIfPresent(vars, "internalCorners", room.getInternalCorners());
        putIfPresent(vars, "ceilingHeight", room.getCeilingHeight());
        return vars;
    }

    private static void putIfPresent(Map<String, BigDecimal> vars, String name, BigDecimal value) {
        if (value != null) {
            vars.put(name, value);
        }
    }

    private static void putIfPresent(Map<String, BigDecimal> vars, String name, Integer value) {
        if (value != null) {
            vars.put(name, BigDecimal.valueOf(value));
        }
    }
}
