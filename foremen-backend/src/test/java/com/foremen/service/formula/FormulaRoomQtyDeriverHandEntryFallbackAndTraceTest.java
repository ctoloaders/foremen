package com.foremen.service.formula;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.formula.FormulaAst;
import com.foremen.dao.model.formula.FormulaAst.BinOp;
import com.foremen.dao.model.formula.FormulaAst.BinOperator;
import com.foremen.dao.model.formula.FormulaAst.Const;
import com.foremen.dao.model.formula.FormulaAst.Var;
import com.foremen.dao.model.formula.FormulaAst.WorkRef;
import com.foremen.dao.model.formula.FormulaAst.WorkRefMode;
import com.foremen.service.formula.FormulaRoomQtyDeriver.DerivationTrace;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FormulaRoomQtyDeriver#deriveForRoom} covering the hand-entry fallback
 * (Requirement 5.4) and the derivation trace's exposure of the winning formula's source text and
 * resolved inputs (Requirement 5.5), per FOR-05-04 task 17.4.
 *
 * <p>No Spring context and no database — {@link FormulaRoomQtyDeriver} is a pure static utility
 * operating on an in-memory {@link RoomEntity} and {@link FormulaAst} map.
 */
class FormulaRoomQtyDeriverHandEntryFallbackAndTraceTest {

    // ------------------------------------------------------------------------------------------
    // Requirement 5.4: a work with no applicable formula is simply absent from the
    // applicableFormulas map, and the deriver never emits/touches anything for it -- leaving a
    // caller's separately-tracked hand-entered EstimateLineRoomQty completely untouched.
    // ------------------------------------------------------------------------------------------

    @Test
    void workWithNoApplicableFormulaHasNoEntryInResult() {
        RoomEntity room = new RoomEntity();
        room.setFloorArea(new BigDecimal("12.5"));
        room.setWallArea(new BigDecimal("30.0"));

        // "X99" has no applicable formula (neither override nor default) -- it is simply absent
        // from the map, representing "this work's quantity is hand-entered".
        Map<String, FormulaAst> applicableFormulas = Map.of(
                "X10", new BinOp(BinOperator.MUL, new Var("floorArea"), new Const(BigDecimal.valueOf(2))));

        Map<String, DerivationTrace> traces = FormulaRoomQtyDeriver.deriveForRoom(room, applicableFormulas);

        assertThat(traces).doesNotContainKey("X99");
        assertThat(traces).containsKey("X10");
    }

    @Test
    void emptyApplicableFormulasYieldsEmptyResult() {
        RoomEntity room = new RoomEntity();
        room.setFloorArea(new BigDecimal("12.5"));

        Map<String, DerivationTrace> traces = FormulaRoomQtyDeriver.deriveForRoom(room, Map.of());

        assertThat(traces).isEmpty();
    }

    @Test
    void nullApplicableFormulasYieldsEmptyResult() {
        RoomEntity room = new RoomEntity();
        room.setFloorArea(new BigDecimal("12.5"));

        Map<String, DerivationTrace> traces = FormulaRoomQtyDeriver.deriveForRoom(room, null);

        assertThat(traces).isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // Requirement 5.5: a derived quantity's DerivationTrace exposes the resolved value, the
    // room-dimension inputs it consumed, and (when available) the formula's source text.
    // ------------------------------------------------------------------------------------------

    @Test
    void derivationTraceExposesResolvedValueAndRoomDimensionInputs() {
        RoomEntity room = new RoomEntity();
        room.setFloorArea(new BigDecimal("12.5"));
        room.setWallArea(new BigDecimal("30.0"));

        FormulaAst floorAreaTimesTwo =
                new BinOp(BinOperator.MUL, new Var("floorArea"), new Const(BigDecimal.valueOf(2)));
        Map<String, FormulaAst> applicableFormulas = Map.of("X10", floorAreaTimesTwo);

        Map<String, DerivationTrace> traces = FormulaRoomQtyDeriver.deriveForRoom(room, applicableFormulas);

        assertThat(traces).containsKey("X10");
        DerivationTrace trace = traces.get("X10");
        assertThat(trace.resolvedValue()).isEqualByComparingTo(new BigDecimal("25.0"));
        assertThat(trace.resolvedInputs().get("floorArea")).isEqualByComparingTo(new BigDecimal("12.5"));
        // formulaSourceText is null on the two-arg overload (a bare FormulaAst carries no source text).
        assertThat(trace.formulaSourceText()).isNull();
    }

    @Test
    void derivationTraceExposesSourceTextWhenSourcesMapProvided() {
        RoomEntity room = new RoomEntity();
        room.setFloorArea(new BigDecimal("12.5"));

        FormulaAst floorAreaTimesTwo =
                new BinOp(BinOperator.MUL, new Var("floorArea"), new Const(BigDecimal.valueOf(2)));
        Map<String, FormulaAst> applicableFormulas = Map.of("X10", floorAreaTimesTwo);
        Map<String, String> applicableFormulaSources = Map.of("X10", "floorArea*2");

        Map<String, DerivationTrace> traces =
                FormulaRoomQtyDeriver.deriveForRoom(room, applicableFormulas, applicableFormulaSources);

        DerivationTrace trace = traces.get("X10");
        assertThat(trace.formulaSourceText()).isEqualTo("floorArea*2");
        assertThat(trace.resolvedValue()).isEqualByComparingTo(new BigDecimal("25.0"));
        assertThat(trace.resolvedInputs().get("floorArea")).isEqualByComparingTo(new BigDecimal("12.5"));
    }

    // ------------------------------------------------------------------------------------------
    // Cross-work reference: a referencing work's resolvedInputs also captures the referenced
    // work's already-resolved value under its own ref key, not just room dimensions.
    // ------------------------------------------------------------------------------------------

    @Test
    void derivationTraceCapturesCrossWorkResolvedInputs() {
        RoomEntity room = new RoomEntity();
        room.setFloorArea(new BigDecimal("10.0"));

        // X20 = floorArea (10.0); X21 = X20 * 3 (references X20's resolved volume).
        FormulaAst floorArea = new Var("floorArea");
        FormulaAst tripleOfX20 =
                new BinOp(BinOperator.MUL, new WorkRef("X20", WorkRefMode.VOLUME), new Const(BigDecimal.valueOf(3)));

        Map<String, FormulaAst> applicableFormulas = Map.of(
                "X20", floorArea,
                "X21", tripleOfX20);

        Map<String, DerivationTrace> traces = FormulaRoomQtyDeriver.deriveForRoom(room, applicableFormulas);

        assertThat(traces).containsKeys("X20", "X21");
        assertThat(traces.get("X20").resolvedValue()).isEqualByComparingTo(new BigDecimal("10.0"));
        assertThat(traces.get("X21").resolvedValue()).isEqualByComparingTo(new BigDecimal("30.0"));

        // X21's resolvedInputs must include X20's already-resolved value under the "X20" key.
        assertThat(traces.get("X21").resolvedInputs()).containsKey("X20");
        assertThat(traces.get("X21").resolvedInputs().get("X20")).isEqualByComparingTo(new BigDecimal("10.0"));
    }
}
