package com.foremen.service.offer;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.foremen.controller.ClientOfferController;

/**
 * FOR-05-04 task 1.3 (Requirements 8.5, 14.4, 17.1, 17.5): reflection contract. No read model that a
 * CLIENT (or the client portal, client PDF, or comparison data) receives has a property whose name
 * starts with {@code ai} ({@code aiGenerated}, {@code aiJobId}, {@code aiReport}, {@code ai}, ...).
 * The AI fields live only on the staff {@link ExecutorOfferReadModel}.
 */
@DisplayName("FOR-05-04: client read models carry no ai property")
class ClientAiRedactionContractTest {

    /** Name starting with the segment ai: ai, aiReport, ai_job_id; not airline. */
    private static final Pattern AI_PREFIX = Pattern.compile("^ai([A-Z_0-9].*)?$");

    private static final List<Class<?>> CLIENT_FACING_ROOTS = List.of(
            ClientOfferReadModel.class,
            AgreedOfferView.class,
            NegotiationRoundView.class,
            AppliedDiscountView.class,
            FinishingSelectionView.class,
            PackagePriceView.class,
            NotificationView.class,
            OfferReadinessView.class,
            // client offer PDF / HTML model and its snapshot view
            OfferHtmlBuilder.Header.class,
            OfferSnapshotViews.SnapshotView.class,
            // offers list and comparison data (served to CLIENT with the redaction flag)
            OfferSnapshotViews.OfferSummary.class,
            OfferSnapshotViews.Comparison.class,
            OfferSnapshotViews.MultiComparison.class,
            // client portal controller DTOs
            ClientOfferController.ChooseConcreteRequest.class,
            ClientOfferController.ChooseConcreteResponse.class);

    @Test
    @DisplayName("no client read model, client PDF model or comparison DTO has a property starting with ai")
    void clientFacingModelsHaveNoAiProperty() {
        List<String> violations = new ArrayList<>();
        for (Class<?> root : CLIENT_FACING_ROOTS) {
            violations.addAll(walk(root));
        }
        assertThat(violations)
                .as("client-facing read models must never expose ai properties (R8.5, R14.4, R17.1, R17.5)")
                .isEmpty();
    }

    @Test
    @DisplayName("the staff ExecutorOfferReadModel carries aiGenerated/aiJobId/aiReport")
    void staffModelCarriesAiFields() {
        Set<String> staff = new HashSet<>();
        for (RecordComponent c : ExecutorOfferReadModel.class.getRecordComponents()) {
            staff.add(c.getName());
        }
        assertThat(staff).contains("aiGenerated", "aiJobId", "aiReport");
        assertThat(walk(ClientOfferReadModel.class)).isEmpty();
    }

    @Test
    @DisplayName("task 3.11: the walker reaches the nested snapshot cell type, which carries no ai (R21.8)")
    void nestedCellTypesAreCheckedAndClean() {
        assertThat(reachable(OfferSnapshotViews.SnapshotView.class))
                .contains(OfferSnapshotViews.RowView.class, OfferSnapshotViews.CellView.class)
                .doesNotContain(OfferSnapshot.Cell.class, com.foremen.service.estimate.CellAiMeta.class);
        // The stored snapshot cell and the staff view do carry ai, so exposing either would be caught.
        assertThat(walk(OfferSnapshot.class)).contains(OfferSnapshot.Cell.class.getName() + ".ai");
        assertThat(walk(OfferSnapshotViews.StaffSnapshotView.class)).isNotEmpty();
    }

    @Test
    @DisplayName("task 3.12: no client-facing model reaches the snapshot material lines or a materials property (R23.10)")
    void clientModelsCarryNoMaterials() {
        List<String> violations = new ArrayList<>();
        for (Class<?> root : CLIENT_FACING_ROOTS) {
            Set<Class<?>> types = reachable(root);
            assertThat(types)
                    .as("%s must not reach the snapshot cell or material line", root.getSimpleName())
                    .doesNotContain(OfferSnapshot.MaterialLine.class, OfferSnapshot.Cell.class);
            for (Class<?> type : types) {
                for (RecordComponent component : type.getRecordComponents()) {
                    if ("materials".equals(component.getName())) {
                        violations.add(type.getName() + "." + component.getName());
                    }
                }
            }
        }
        assertThat(violations).as("client-facing read models must never expose material lines").isEmpty();
        // The stored snapshot does carry them, so exposing it to a client would be caught.
        assertThat(reachable(OfferSnapshot.class)).contains(OfferSnapshot.Cell.class, OfferSnapshot.MaterialLine.class);
        assertThat(reachable(OfferSnapshotViews.SnapshotView.class))
                .doesNotContain(OfferSnapshot.MaterialLine.class);
    }

    /** Every record type reachable from {@code root} through record components. */
    private static Set<Class<?>> reachable(Class<?> root) {
        Deque<Class<?>> pending = new ArrayDeque<>();
        Set<Class<?>> visited = new HashSet<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Class<?> type = pending.pop();
            if (!visited.add(type) || !type.isRecord()) {
                continue;
            }
            for (RecordComponent component : type.getRecordComponents()) {
                collectRecords(component.getGenericType(), pending);
            }
        }
        return visited;
    }

    @Test
    @DisplayName("task 9.6 (R25.4): aiSummary lives only on the staff estimate matrix, never on a client model")
    void aiSummaryIsStaffOnly() {
        Set<String> matrix = new HashSet<>();
        for (RecordComponent c : com.foremen.service.estimate.matrix.EstimateMatrixDto.class.getRecordComponents()) {
            matrix.add(c.getName());
        }
        assertThat(matrix).contains("aiSummary");
        assertThat(walk(com.foremen.service.estimate.matrix.EstimateMatrixDto.class))
                .contains(com.foremen.service.estimate.matrix.EstimateMatrixDto.class.getName() + ".aiSummary");
        for (Class<?> root : CLIENT_FACING_ROOTS) {
            assertThat(reachable(root))
                    .as("%s must not reach the estimate matrix", root.getSimpleName())
                    .doesNotContain(com.foremen.service.estimate.matrix.EstimateMatrixDto.class);
        }
    }

    @Test
    @DisplayName("the contract walker detects an ai property (self-check)")
    void walkerDetectsViolation() {
        assertThat(walk(Leaky.class)).hasSize(2);
        assertThat(AI_PREFIX.matcher("airline").matches()).isFalse();
    }

    private record Leaky(String name, Boolean aiGenerated, Nested nested) {
    }

    private record Nested(List<String> aiReport) {
    }

    private static List<String> walk(Class<?> root) {
        List<String> violations = new ArrayList<>();
        Deque<Class<?>> pending = new ArrayDeque<>();
        Set<Class<?>> visited = new HashSet<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Class<?> type = pending.pop();
            if (!visited.add(type) || !type.isRecord()) {
                continue;
            }
            for (RecordComponent component : type.getRecordComponents()) {
                if (AI_PREFIX.matcher(component.getName()).matches()) {
                    violations.add(type.getName() + "." + component.getName());
                }
                collectRecords(component.getGenericType(), pending);
            }
        }
        return violations;
    }

    private static void collectRecords(Type type, Deque<Class<?>> out) {
        if (type instanceof Class<?> clazz) {
            if (clazz.isRecord()) {
                out.push(clazz);
            }
        } else if (type instanceof ParameterizedType parameterized) {
            for (Type arg : parameterized.getActualTypeArguments()) {
                collectRecords(arg, out);
            }
        }
    }
}
