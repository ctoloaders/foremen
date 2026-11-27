package com.foremen.service.estimateai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.estimate.CellMetaInput;
import com.foremen.service.estimate.LabourPriceAdjustmentScope;

/**
 * FOR-05-04 task 9.2 - pure rules of {@link AssignWorkService}, the tool argument handling and the lock guard
 * with an {@link AiJobContext}. The committed-and-visible behaviour is covered by {@code AssignWorkServiceIT}.
 *
 * <p>Validates: Requirements 24.2, 24.6, 24.7, 6.2, 6.4
 */
class AssignWorkServiceTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    // ------------------------------------------------------------------ tolerance (24.6, 6.2)

    @Test
    @DisplayName("volume within the tolerance keeps the formula volume; beyond it becomes an override")
    void toleranceBoundary() {
        assertThat(AssignWorkService.needsOverride(d("10.5"), d("10"), 5.0)).isFalse(); // exactly 5%
        assertThat(AssignWorkService.needsOverride(d("10.51"), d("10"), 5.0)).isTrue();
        assertThat(AssignWorkService.needsOverride(d("9.4"), d("10"), 5.0)).isTrue();
        assertThat(AssignWorkService.needsOverride(d("10"), d("10"), 5.0)).isFalse();
    }

    @Test
    @DisplayName("no model volume, a zero volume or a negative one never overrides")
    void noVolumeNoOverride() {
        assertThat(AssignWorkService.needsOverride(null, d("10"), 5.0)).isFalse();
        assertThat(AssignWorkService.needsOverride(BigDecimal.ZERO, d("10"), 5.0)).isFalse();
        assertThat(AssignWorkService.needsOverride(d("-1"), d("10"), 5.0)).isFalse();
    }

    @Test
    @DisplayName("a positive model volume overrides when the formula gives nothing")
    void formulaMissing() {
        assertThat(AssignWorkService.needsOverride(d("3"), null, 5.0)).isTrue();
        assertThat(AssignWorkService.needsOverride(d("3"), BigDecimal.ZERO, 5.0)).isTrue();
    }

    // ------------------------------------------------------------------ coefficient only when stated (6.4)

    @Test
    @DisplayName("only a complete, non-zero coefficient is applied")
    void coefficientOnlyWhenStated() {
        assertThat(AssignWorkService.validCoefficient(null)).isFalse();
        assertThat(AssignWorkService.validCoefficient(
                new AssignWorkCommand.Coefficient(null, null, d("10")))).isFalse();
        assertThat(AssignWorkService.validCoefficient(
                new AssignWorkCommand.Coefficient(LabourPriceAdjustmentScope.PROJECT, null, null))).isFalse();
        assertThat(AssignWorkService.validCoefficient(
                new AssignWorkCommand.Coefficient(LabourPriceAdjustmentScope.PROJECT, null, BigDecimal.ZERO)))
                .isFalse();
        assertThat(AssignWorkService.validCoefficient(
                new AssignWorkCommand.Coefficient(LabourPriceAdjustmentScope.WORK_ITEM, null, d("10")))).isFalse();
        assertThat(AssignWorkService.validCoefficient(
                new AssignWorkCommand.Coefficient(LabourPriceAdjustmentScope.PROJECT, null, d("10")))).isTrue();
        assertThat(AssignWorkService.validCoefficient(
                new AssignWorkCommand.Coefficient(LabourPriceAdjustmentScope.WORK_ITEM, 5L, d("-10")))).isTrue();
    }

    @Test
    @DisplayName("price reason is carried only with a stated coefficient; volume reason only with an override")
    void metaCarriesReasonsOnlyWhenApplicable() {
        WorkItemEntity work = mock(WorkItemEntity.class);
        when(work.getCode()).thenReturn("W-1");
        AssignWorkCommand noCoefficient = new AssignWorkCommand(1L, List.of(7L), d("12"), "plan says 12", "why",
                "CONFIRMED", d("0.9"), "plan.pdf, p.3", "premium", List.of());
        CellMetaInput a = AssignWorkService.meta(work, 7L, noCoefficient, false);
        assertThat(a.priceReason()).isNull();
        assertThat(a.volumeReason()).isNull();
        assertThat(a.sourceFile()).isEqualTo("plan.pdf");
        assertThat(a.sourcePage()).isEqualTo(3);
        assertThat(a.origin()).isEqualTo("JOB");

        AssignWorkCommand withCoefficient = new AssignWorkCommand(1L, List.of(7L), d("12"), "plan says 12", "why",
                "CONFIRMED", null, null, "premium",
                List.of(new AssignWorkCommand.Coefficient(LabourPriceAdjustmentScope.PROJECT, null, d("10"))));
        CellMetaInput b = AssignWorkService.meta(work, 7L, withCoefficient, true);
        assertThat(b.priceReason()).isEqualTo("premium");
        assertThat(b.volumeReason()).isEqualTo("plan says 12");
        assertThat(b.volumeOverridden()).isTrue();
    }

    // ------------------------------------------------------------------ tool arguments (24.1, 24.7)

    @Test
    @DisplayName("the declaration names assign_work and its mandatory arguments")
    void declaration() {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode d = new AssignWorkTool(null).declaration();
        assertThat(d.get("name").asText()).isEqualTo("assign_work");
        assertThat(d.get("parameters").get("properties").fieldNames()).toIterable().contains(
                "workItemId", "roomIds", "volume", "volumeReason", "rationale", "status", "confidence", "source",
                "priceReason");
        assertThat(d.get("parameters").get("required").toString()).contains("rationale", "roomIds");
    }

    @Test
    @DisplayName("model arguments never carry a price coefficient; the stated ones come from the caller")
    void toolParse() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode args = mapper.readTree("{\"workItemId\":4,\"roomIds\":[1,2],\"volume\":12.5,"
                + "\"rationale\":\"r\",\"status\":\"ASSUMED\",\"coefficient\":50}");
        AssignWorkCommand cmd = AssignWorkTool.parse(args, List.of());
        assertThat(cmd.workItemId()).isEqualTo(4L);
        assertThat(cmd.roomIds()).containsExactly(1L, 2L);
        assertThat(cmd.volume()).isEqualByComparingTo("12.5");
        assertThat(cmd.coefficients()).isEmpty();
        assertThatThrownBy(() -> AssignWorkTool.parse(mapper.readTree("{\"roomIds\":[\"x\"]}"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an unparsable call is an error result, a rejected one carries the typed code")
    void toolReturnsErrors() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AssignWorkService service = mock(AssignWorkService.class);
        AssignWorkTool tool = new AssignWorkTool(service);
        AiJobContext ctx = new AiJobContext(1L, 2L);
        assertThat(tool.run(ctx, mapper.readTree("{\"roomIds\":[\"x\"]}"), List.of()).error())
                .isEqualTo(AssignWorkTool.ERR_ARGS);

        when(service.assign(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new AssignRejected(WarningCodes.NO_PRICE, java.util.Map.of("workItemId", 4L)));
        var out = tool.run(ctx, mapper.readTree("{\"workItemId\":4,\"roomIds\":[1],\"rationale\":\"r\"}"),
                List.of());
        assertThat(out.ok()).isFalse();
        assertThat(out.error()).isEqualTo(WarningCodes.NO_PRICE);
    }

    // ------------------------------------------------------------------ lock bypass only for the owning job

    private static RoomsImportJobEntity job(long id) {
        RoomsImportJobEntity j = new RoomsImportJobEntity();
        j.setId(id);
        return j;
    }

    @Test
    @DisplayName("the lock guard passes the project's own active job and refuses any other")
    void lockBypassOnlyForOwningJob() {
        RoomsImportJobDao dao = mock(RoomsImportJobDao.class);
        when(dao.findActiveEstimateJob(anyLong())).thenReturn(Optional.of(job(10L)));
        AiProjectLockGuard guard = new AiProjectLockGuard(dao);

        guard.assertUnlocked(5L, new AiJobContext(10L, 5L)); // owning job: passes

        assertThatThrownBy(() -> guard.assertUnlocked(5L, new AiJobContext(11L, 5L)))
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining(AiProjectLockGuard.LOCKED_MESSAGE);
        assertThatThrownBy(() -> guard.assertUnlocked(5L, (AiJobContext) null))
                .isInstanceOf(ForemenApiException.class);
        assertThatThrownBy(() -> guard.assertUnlocked(5L))
                .isInstanceOf(ForemenApiException.class);
    }

    @Test
    @DisplayName("without an active job the guard locks nothing")
    void noActiveJobNoLock() {
        RoomsImportJobDao dao = mock(RoomsImportJobDao.class);
        when(dao.findActiveEstimateJob(anyLong())).thenReturn(Optional.empty());
        AiProjectLockGuard guard = new AiProjectLockGuard(dao);
        guard.assertUnlocked(5L, new AiJobContext(10L, 5L));
        guard.assertUnlocked(5L, (AiJobContext) null);
    }
}
