package com.foremen.service.formula;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkPackageOverrideEntity;
import com.foremen.dao.model.WorkVolumeFormulaEntity;
import com.foremen.dao.model.formula.FormulaAst;
import com.foremen.dao.model.formula.FormulaAst.WorkRef;

/**
 * Derives a room's formula-driven work volumes and their per-line derivation trace (FOR-05-04
 * §Components Component 5, §6.4/§6.5, task 17.2).
 *
 * <p><b>Two-part split.</b> This class deliberately separates the DB-aware "which formula
 * applies" decision from the pure "plan + evaluate" core, mirroring
 * {@link FormulaEvaluationPlanner}/{@link FormulaEvaluator}'s stateless style:
 * <ul>
 *   <li>{@link #resolveApplicableFormula(WorkPackageOverrideEntity, WorkVolumeFormulaEntity)} —
 *       the §6.5 {@code applicableFormula} package-context precedence rule (Requirement 4.2,
 *       5.2, 5.3, 5.4). It reads only the two already-fetched entities handed to it (no DAO
 *       access itself); the caller (a future service, task 17.3/20.1) is responsible for
 *       fetching the {@link WorkPackageOverrideEntity} for {@code (workItem, activePackage)}
 *       and the work's default {@link WorkVolumeFormulaEntity} and passing them in.</li>
 *   <li>{@link #deriveForRoom(RoomEntity, Map)} — the §6.4 {@code evaluateRoom} pure core
 *       (Requirement 5.3, 5.5): given a room and the map of already-resolved active formulas
 *       (work ref → {@link FormulaAst}), populates {@link RoomVariables}, plans the deterministic
 *       evaluation order, evaluates every formula in that order, and emits a
 *       {@link DerivationTrace} per derived work. It performs no entity/DAO lookups and has no
 *       side effects — same inputs always yield the same output (Requirement 3.5).</li>
 * </ul>
 *
 * <p>A work item with no applicable formula (neither an override nor a default) simply has no
 * entry in the {@code applicableFormulas} map passed to {@link #deriveForRoom}; this deriver
 * never touches such a work's quantity — the caller leaves its hand-entered
 * {@code EstimateLineRoomQty} untouched (Requirement 5.4).
 */
public final class FormulaRoomQtyDeriver {

    private FormulaRoomQtyDeriver() {
    }

    /**
     * Resolves the formula that applies to a work item for a given package context, per the
     * §6.5 {@code applicableFormula} precedence rule:
     * <ol>
     *   <li>if {@code override} exists and carries a non-null {@code overrideParsedAst}, that
     *       override formula wins, regardless of its {@code member} flag's value (a present
     *       override formula always implies membership, Requirement 4.2);</li>
     *   <li>else, if {@code defaultFormula} exists, its {@code parsedAst} applies (Requirement
     *       5.3);</li>
     *   <li>else there is no applicable formula — {@code null} — and the caller must fall back
     *       to hand-entered {@code EstimateLineRoomQty} (Requirement 5.4).</li>
     * </ol>
     *
     * <p>Note: a {@code WorkPackageOverrideEntity} row that exists but carries no override
     * formula (flag-only membership/exclusion, {@code overrideSourceText} absent) does not by
     * itself suppress the default formula — Requirement 4.3 only says membership is "not
     * established by this mechanism" when no override formula is present; it does not say the
     * default formula must be skipped. Callers that need package-exclusion semantics beyond
     * "no formula override" apply that policy on top of this method's result.
     *
     * @param override      the {@code (workItem, activePackage)} override row, or {@code null}
     *                       if none exists for the pair
     * @param defaultFormula the work's default volume formula row, or {@code null} if the work
     *                       has no default formula
     * @return the winning {@link FormulaAst}, or {@code null} if no formula applies
     */
    public static FormulaAst resolveApplicableFormula(WorkPackageOverrideEntity override,
                                                        WorkVolumeFormulaEntity defaultFormula) {
        if (override != null && override.getOverrideParsedAst() != null) {
            return override.getOverrideParsedAst();
        }
        if (defaultFormula != null) {
            return defaultFormula.getParsedAst();
        }
        return null;
    }

    /**
     * Pure core: plans and evaluates every formula in {@code applicableFormulas} against
     * {@code room}'s dimensions, and returns a {@link DerivationTrace} per derived work ref
     * (§6.4 {@code evaluateRoom}, Requirement 5.3, 5.5).
     *
     * @param room               the room whose 14 dimension columns populate {@link RoomVariables}
     * @param applicableFormulas map of work ref (matching {@code WorkItemEntity.getCode()}) to
     *                           that work's ALREADY package-precedence-resolved formula (see
     *                           {@link #resolveApplicableFormula}) for every work in the room
     *                           whose quantity is formula-driven; a work with no applicable
     *                           formula MUST simply be absent from this map
     * @return a map of work ref to {@link DerivationTrace}, one entry per key of
     *         {@code applicableFormulas}
     * @throws com.foremen.exception.ForemenApiException {@code 409 error.formula.cycle} if the
     *                              formulas' cross-work references form a cycle (delegated to
     *                              {@link FormulaEvaluationPlanner#planOrder}); {@code 400
     *                              error.formula.division.by.zero} if evaluation divides by zero
     */
    public static Map<String, DerivationTrace> deriveForRoom(RoomEntity room,
                                                               Map<String, FormulaAst> applicableFormulas) {
        if (applicableFormulas == null || applicableFormulas.isEmpty()) {
            return Map.of();
        }

        Map<String, BigDecimal> vars = roomVariables(room);
        List<String> order = FormulaEvaluationPlanner.planOrder(applicableFormulas);

        Map<String, BigDecimal> resolved = new HashMap<>();
        Map<String, DerivationTrace> traces = new HashMap<>();

        for (String workRef : order) {
            FormulaAst ast = applicableFormulas.get(workRef);
            BigDecimal value = FormulaEvaluator.evaluate(ast, vars,
                    (WorkRef ref) -> resolved.getOrDefault(ref.ref(), BigDecimal.ZERO));
            resolved.put(workRef, value);

            // resolvedInputs: the room-dimension variables plus every cross-work volume already
            // resolved for this room, so the trace shows everything a formula in this room COULD
            // have consumed (isolating exactly which subset a given formula used is impractical
            // without re-walking the AST, and design.md's §6.4/Component 5 javadoc only requires
            // the derivation to be traceable, not minimally scoped per formula).
            Map<String, BigDecimal> resolvedInputs = new HashMap<>(vars);
            resolvedInputs.putAll(resolved);

            // formulaSourceText is left null here: the pure core operates on FormulaAst alone,
            // which carries no source text of its own. Callers that need the human-readable
            // source in the trace should use the deriveForRoom(room, formulas, sources) overload.
            traces.put(workRef, new DerivationTrace(null, resolvedInputs, value));
        }

        return traces;
    }

    /**
     * Builds the {@link RoomVariables} map from {@code room}'s 14 dimension columns. A
     * {@code null} column is omitted rather than inserted as {@link BigDecimal#ZERO}, so
     * {@link FormulaEvaluator}'s own "missing var -> 0" contract (see its {@code Var} case)
     * remains the single source of that behaviour instead of being duplicated here. Integer-typed
     * columns ({@code doorCount}, {@code internalCorners}) are converted to {@link BigDecimal}.
     */
    private static Map<String, BigDecimal> roomVariables(RoomEntity room) {
        Map<String, BigDecimal> vars = new HashMap<>();
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

    /**
     * Overload that also carries each formula's human-readable source text into the emitted
     * {@link DerivationTrace#formulaSourceText()} (Requirement 5.5). Prefer this overload when the
     * source text is available (i.e. the caller resolved formulas via
     * {@link #resolveApplicableFormula} and also has the winning entity's source text at hand).
     *
     * @param room                     the room whose dimensions populate {@link RoomVariables}
     * @param applicableFormulas       work ref -> resolved {@link FormulaAst}, as in
     *                                 {@link #deriveForRoom(RoomEntity, Map)}
     * @param applicableFormulaSources work ref -> the winning formula's source text (must have
     *                                 the same key set as {@code applicableFormulas})
     */
    public static Map<String, DerivationTrace> deriveForRoom(RoomEntity room,
                                                               Map<String, FormulaAst> applicableFormulas,
                                                               Map<String, String> applicableFormulaSources) {
        Map<String, DerivationTrace> withoutSource = deriveForRoom(room, applicableFormulas);
        if (withoutSource.isEmpty()) {
            return withoutSource;
        }
        Map<String, DerivationTrace> withSource = new HashMap<>();
        for (Map.Entry<String, DerivationTrace> entry : withoutSource.entrySet()) {
            String workRef = entry.getKey();
            DerivationTrace trace = entry.getValue();
            String sourceText = applicableFormulaSources != null ? applicableFormulaSources.get(workRef) : null;
            withSource.put(workRef, new DerivationTrace(sourceText, trace.resolvedInputs(), trace.resolvedValue()));
        }
        return withSource;
    }

    /**
     * A derived work's derivation trace (Requirement 5.5): the winning formula's source text,
     * the room-dimension + cross-work inputs resolved at the time this work was evaluated, and
     * the resulting resolved quantity.
     *
     * @param formulaSourceText the winning formula's human-readable source text (nullable if the
     *                          caller used {@link #deriveForRoom(RoomEntity, Map)} without a
     *                          source-text map)
     * @param resolvedInputs    room-dimension variables plus every cross-work volume resolved
     *                          for this room by the time this work's formula was evaluated
     * @param resolvedValue     this work's resolved quantity for the room
     */
    public record DerivationTrace(String formulaSourceText, Map<String, BigDecimal> resolvedInputs,
                                    BigDecimal resolvedValue) {
    }
}
