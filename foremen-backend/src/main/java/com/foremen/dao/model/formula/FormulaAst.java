package com.foremen.dao.model.formula;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Tagged AST for a work volume formula (FOR-05-04 §Components Component 4), bound to a
 * {@code jsonb} column via {@code @JdbcTypeCode(SqlTypes.JSON)} on the owning entity
 * ({@code WorkVolumeFormulaEntity.parsedAst}, {@code WorkPackageOverrideEntity.overrideParsedAst})
 * and serialized/deserialized by Jackson using the {@code k} discriminator field.
 *
 * <p>Node kinds (source: Requirements 2.1, 2.2, 2.6, 3.1; design §Components Component 4):
 * <ul>
 *   <li>{@link Const} — a numeric literal, e.g. {@code 2}, {@code 1.5}.</li>
 *   <li>{@link Var} — a named room-dimension variable (one of {@link #ROOM_DIMENSION_VARS}).</li>
 *   <li>{@link WorkRef} — a cross-work reference to another work's computed volume or
 *       presence (quantity {@code > 0}) in the same room.</li>
 *   <li>{@link BinOp} — a binary arithmetic operator ({@code + - * /}).</li>
 *   <li>{@link Cond} — an {@code IFS}-style conditional: the first matching case's value,
 *       else an optional {@code else} expression.</li>
 *   <li>{@link Count} — a {@code COUNTIF}-style boolean presence test (subject compared
 *       against a value).</li>
 * </ul>
 *
 * <p>This model is intentionally pure/stateless: it carries no evaluation logic itself
 * (that belongs to {@code FormulaEvaluator}/{@code FormulaParser}/{@code FormulaValidator},
 * task 8+), only the validated shape produced by parsing and persisted for repeated
 * evaluation without re-parsing (Requirement 2.6).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "k")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FormulaAst.Const.class, name = "const"),
        @JsonSubTypes.Type(value = FormulaAst.Var.class, name = "var"),
        @JsonSubTypes.Type(value = FormulaAst.WorkRef.class, name = "workRef"),
        @JsonSubTypes.Type(value = FormulaAst.BinOp.class, name = "op"),
        @JsonSubTypes.Type(value = FormulaAst.Cond.class, name = "ifs"),
        @JsonSubTypes.Type(value = FormulaAst.Count.class, name = "countif"),
})
public sealed interface FormulaAst
        permits FormulaAst.Const, FormulaAst.Var, FormulaAst.WorkRef, FormulaAst.BinOp,
        FormulaAst.Cond, FormulaAst.Count {

    /**
     * The 14 room-dimension variable names exposed by the formula language, bound 1:1 to
     * {@code RoomEntity}'s dimension columns (§Data Models "Room-dimension variable binding").
     * Order matches the design document's binding table.
     */
    Set<String> ROOM_DIMENSION_VARS = Set.of(
            "floorArea",
            "wallArea",
            "perimeter",
            "doorCount",
            "doorHeight",
            "doorWidth",
            "doorArea",
            "wallGap",
            "finishGap",
            "windowHeight",
            "windowWidth",
            "windowArea",
            "internalCorners",
            "ceilingHeight"
    );

    /** A numeric literal, e.g. {@code 2}, {@code 1.5}. */
    record Const(BigDecimal v) implements FormulaAst {
    }

    /** A named room-dimension variable; {@code name} must be one of {@link #ROOM_DIMENSION_VARS}. */
    record Var(String name) implements FormulaAst {
    }

    /**
     * A cross-work reference to another work's computed volume or presence in the same room
     * (Requirement 3.1).
     *
     * @param ref  the source cell/work reference (e.g. {@code "X28"}, {@code "AL48"}), matching
     *             the {@code Oferta} sheet's cell notation used to identify the referenced work.
     * @param mode {@link WorkRefMode#VOLUME} to read the referenced work's resolved quantity,
     *             {@link WorkRefMode#PRESENT} to read {@code 1} if that quantity is greater than
     *             zero, else {@code 0}.
     */
    record WorkRef(String ref, WorkRefMode mode) implements FormulaAst {
    }

    /** Mode of a {@link WorkRef} operand. */
    enum WorkRefMode {
        VOLUME,
        PRESENT
    }

    /** A binary arithmetic operator over two sub-expressions. */
    record BinOp(BinOperator op, FormulaAst l, FormulaAst r) implements FormulaAst {
    }

    /** Arithmetic operators supported by the formula language (Requirement 2.3). */
    enum BinOperator {
        ADD,
        SUB,
        MUL,
        DIV
    }

    /**
     * An {@code IFS}-style conditional: the value of the first matching {@link CondCase}, else
     * {@code elseExpr} (if present), else the evaluator's zero default.
     */
    record Cond(List<CondCase> cases, FormulaAst elseExpr) implements FormulaAst {
    }

    /**
     * One {@code IFS} case: a comparison ({@code when}) and the expression to yield
     * ({@code then}) if that comparison holds. Not itself a node kind — only ever appears
     * nested inside a {@link Cond#cases()} list.
     */
    record CondCase(Comparison when, FormulaAst then) {
    }

    /**
     * A {@code COUNTIF}-style boolean presence test: does {@code subject} satisfy {@code cmp}
     * against {@code value}? Evaluates to {@code 1} if true, else {@code 0}.
     */
    record Count(FormulaAst subject, CompareOperator cmp, FormulaAst value) implements FormulaAst {
    }

    /** A single comparison used inside a {@link CondCase#when()} or {@link Count}. */
    record Comparison(FormulaAst left, CompareOperator cmp, FormulaAst right) {
    }

    /** Comparison operators supported by {@code IFS}/{@code COUNTIF} conditions. */
    enum CompareOperator {
        EQ,
        NEQ,
        LT,
        LTE,
        GT,
        GTE
    }
}
