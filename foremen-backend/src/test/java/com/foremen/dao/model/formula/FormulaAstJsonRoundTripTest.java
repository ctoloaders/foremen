package com.foremen.dao.model.formula;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.foremen.dao.model.formula.FormulaAst.BinOp;
import com.foremen.dao.model.formula.FormulaAst.BinOperator;
import com.foremen.dao.model.formula.FormulaAst.CompareOperator;
import com.foremen.dao.model.formula.FormulaAst.Comparison;
import com.foremen.dao.model.formula.FormulaAst.Cond;
import com.foremen.dao.model.formula.FormulaAst.CondCase;
import com.foremen.dao.model.formula.FormulaAst.Const;
import com.foremen.dao.model.formula.FormulaAst.Count;
import com.foremen.dao.model.formula.FormulaAst.Var;
import com.foremen.dao.model.formula.FormulaAst.WorkRef;
import com.foremen.dao.model.formula.FormulaAst.WorkRefMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FormulaAst} JSON round-trip via Jackson, covering every node kind
 * plus a nested/composite example and polymorphic round-trip through the sealed interface
 * type (the {@code k} discriminator, {@code @JsonTypeInfo}/{@code @JsonSubTypes}).
 *
 * <p>Validates: Requirements 2.6 (persist the volume formula as a validated/parsed form
 * sufficient for repeated evaluation without re-parsing — i.e. it must survive a JSON
 * round-trip intact, since it is stored as {@code jsonb}).
 */
class FormulaAstJsonRoundTripTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void constRoundTripsThroughConcreteType() throws Exception {
        Const original = new Const(new BigDecimal("1.5"));

        String json = objectMapper.writeValueAsString(original);
        Const roundTripped = objectMapper.readValue(json, Const.class);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void varRoundTripsThroughConcreteType() throws Exception {
        Var original = new Var("floorArea");

        String json = objectMapper.writeValueAsString(original);
        Var roundTripped = objectMapper.readValue(json, Var.class);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void workRefVolumeModeRoundTripsThroughConcreteType() throws Exception {
        WorkRef original = new WorkRef("X28", WorkRefMode.VOLUME);

        String json = objectMapper.writeValueAsString(original);
        WorkRef roundTripped = objectMapper.readValue(json, WorkRef.class);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void workRefPresentModeRoundTripsThroughConcreteType() throws Exception {
        WorkRef original = new WorkRef("AL48", WorkRefMode.PRESENT);

        String json = objectMapper.writeValueAsString(original);
        WorkRef roundTripped = objectMapper.readValue(json, WorkRef.class);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void binOpRoundTripsForEachOperator() throws Exception {
        for (BinOperator op : BinOperator.values()) {
            BinOp original = new BinOp(op, new Const(new BigDecimal("2")), new Var("wallArea"));

            String json = objectMapper.writeValueAsString(original);
            BinOp roundTripped = objectMapper.readValue(json, BinOp.class);

            assertThat(roundTripped).as("operator %s", op).isEqualTo(original);
        }
    }

    @Test
    void condRoundTripsWithCasesAndElse() throws Exception {
        Cond original = new Cond(
                List.of(
                        new CondCase(
                                new Comparison(new Var("doorCount"), CompareOperator.EQ, new Const(BigDecimal.ONE)),
                                new Const(new BigDecimal("3"))),
                        new CondCase(
                                new Comparison(new Var("doorCount"), CompareOperator.GT, new Const(BigDecimal.ONE)),
                                new Const(new BigDecimal("6")))),
                new Const(BigDecimal.ZERO));

        String json = objectMapper.writeValueAsString(original);
        Cond roundTripped = objectMapper.readValue(json, Cond.class);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void condRoundTripsWithoutElse() throws Exception {
        Cond original = new Cond(
                List.of(new CondCase(
                        new Comparison(new Var("windowArea"), CompareOperator.NEQ, new Const(BigDecimal.ZERO)),
                        new Const(BigDecimal.ONE))),
                null);

        String json = objectMapper.writeValueAsString(original);
        Cond roundTripped = objectMapper.readValue(json, Cond.class);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void countRoundTripsForEachComparator() throws Exception {
        for (CompareOperator cmp : CompareOperator.values()) {
            Count original = new Count(new Var("internalCorners"), cmp, new Const(new BigDecimal("4")));

            String json = objectMapper.writeValueAsString(original);
            Count roundTripped = objectMapper.readValue(json, Count.class);

            assertThat(roundTripped).as("comparator %s", cmp).isEqualTo(original);
        }
    }

    @Test
    void nestedCompositeExpressionRoundTripsThroughConcreteType() throws Exception {
        // BinOp( Cond( [ perimeter > 0 -> WorkRef(X28, VOLUME) ], else = Const(0) ), MUL, Const(2) )
        BinOp original = new BinOp(
                BinOperator.MUL,
                new Cond(
                        List.of(new CondCase(
                                new Comparison(new Var("perimeter"), CompareOperator.GT, new Const(BigDecimal.ZERO)),
                                new WorkRef("X28", WorkRefMode.VOLUME))),
                        new Const(BigDecimal.ZERO)),
                new Const(new BigDecimal("2")));

        String json = objectMapper.writeValueAsString(original);
        BinOp roundTripped = objectMapper.readValue(json, BinOp.class);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void everyNodeKindRoundTripsPolymorphicallyThroughTheSealedInterfaceType() throws Exception {
        List<FormulaAst> originals = List.of(
                new Const(new BigDecimal("42")),
                new Var("ceilingHeight"),
                new WorkRef("AM12", WorkRefMode.PRESENT),
                new BinOp(BinOperator.SUB, new Var("wallArea"), new Var("doorArea")),
                new Cond(
                        List.of(new CondCase(
                                new Comparison(new Var("doorCount"), CompareOperator.EQ, new Const(BigDecimal.ONE)),
                                new Const(new BigDecimal("3")))),
                        new Const(BigDecimal.ZERO)),
                new Count(new Var("windowArea"), CompareOperator.GTE, new Const(new BigDecimal("1.2"))));

        for (FormulaAst original : originals) {
            String json = objectMapper.writeValueAsString(original);
            FormulaAst roundTripped = objectMapper.readValue(json, FormulaAst.class);

            assertThat(roundTripped)
                    .as("polymorphic round-trip for %s", original.getClass().getSimpleName())
                    .isEqualTo(original);
            assertThat(roundTripped)
                    .as("discriminator must resolve back to the original concrete type")
                    .isInstanceOf(original.getClass());
        }
    }

    @Test
    void nestedCompositeExpressionRoundTripsPolymorphicallyThroughTheSealedInterfaceType() throws Exception {
        FormulaAst original = new BinOp(
                BinOperator.ADD,
                new WorkRef("X42", WorkRefMode.VOLUME),
                new Cond(
                        List.of(new CondCase(
                                new Comparison(new Var("floorArea"), CompareOperator.LTE, new Const(new BigDecimal("10"))),
                                new WorkRef("X28", WorkRefMode.VOLUME))),
                        new Const(BigDecimal.ZERO)));

        String json = objectMapper.writeValueAsString(original);
        FormulaAst roundTripped = objectMapper.readValue(json, FormulaAst.class);

        assertThat(roundTripped).isEqualTo(original);
    }
}
