package com.foremen.service.document;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * FOR-05-08 (task 11.4) — structural confidentiality test: no client-reachable DTO in
 * {@code com.foremen.service.document} carries a field whose name denotes a cost / margin /
 * estimate / price figure (Requirements 9.6, 13.3).
 *
 * <p>This is a <b>plain reflection unit test</b> — no Spring context, no Testcontainers. It mirrors
 * the Property-21 confidentiality guard the Offer stage ships
 * ({@code OfferControllersAbacTest.Confidentiality},
 * {@code OfferEntityAndReadModelStructuralTest}): it transitively walks the record-component type
 * graph rooted at every client-reachable document DTO and asserts no component name contains a
 * confidential word.
 *
 * <p><b>Why this is the confidentiality invariant that matters (R9.6, R13.3).</b> The signing module
 * deliberately references the originating object (an approved offer, an estimate) only as the opaque
 * {@link SignableDocumentDto#sourceRef()} string — never as an embedded estimate/offer DTO. A CLIENT
 * (and every other role) is served exactly the {@link SignableDocumentDto} graph plus the admin
 * reference DTOs; if any component in that graph were named {@code cost}/{@code margin}/{@code
 * estimate}/{@code price}, a confidential figure could leak into a client-reachable payload. Walking
 * the whole transitive graph (records, their component types, and {@code List<...>} element types)
 * means adding such a field anywhere in the graph fails this test.
 *
 * <p><b>Word-boundary matching.</b> Component names are split into camelCase / snake_case words and a
 * violation is a whole-word match against the forbidden set, so legitimate names are not
 * false-positives: {@code documentTypeCode}, {@code templateLocale}, {@code storageUri},
 * {@code contentHash}, {@code sourceRef}, {@code signedCount} etc. all pass, while {@code unitPrice},
 * {@code offerCost}, {@code marginNet}, {@code estimateTotal} would each be caught.
 *
 * <p>Validates: Requirements 9.6, 13.3
 */
@DisplayName("FOR-05-08 confidentiality — no client-reachable document DTO carries a cost/margin/estimate/price field (R9.6, R13.3)")
@Tag("Feature: FOR-05-08-document-signing, task 11.4: structural confidentiality test")
class DocumentDtoConfidentialityStructuralTest {

    /**
     * Confidential whole-words that must never name a component anywhere in the client-reachable DTO
     * graph. Matched case-insensitively against camelCase / snake_case word splits (see
     * {@link #splitWords}), so a legitimate field like {@code contentHash} / {@code templateLocale} /
     * {@code sourceRef} is never a false positive — only a word equal to one of these trips it.
     */
    private static final Set<String> CONFIDENTIAL_WORDS = Set.of(
            "cost", "costs",
            "margin", "margins",
            "markup",
            "estimate", "estimates",
            "price", "prices", "pricing",
            "selfcost", "sebestoim");

    /**
     * The client-reachable DTO roots of this module — every type returned by a
     * {@code /api/signable-documents}, {@code /api/document-templates},
     * {@code /api/signable-document-types}, or {@code /api/company-profile} response. The transitive
     * walk expands each into its nested record components, so this list is the set of graph roots,
     * not the full set of checked types.
     */
    private static final List<Class<?>> CLIENT_REACHABLE_ROOTS = List.of(
            SignableDocumentDto.class,
            DocumentSignatureDto.class,
            DocumentMediaSummaryDto.class,
            FormFieldDto.class,
            SigningProgressDto.class,
            DocumentTemplateDto.class,
            TestMergeResultDto.class,
            SignableDocumentTypeDto.class,
            CompanyProfileDto.class);

    @Test
    @DisplayName("the transitive graph of every client-reachable document DTO has no cost/margin/estimate/price field")
    void clientReachableDtoGraphHasNoConfidentialField() {
        List<String> violations = new ArrayList<>();
        for (Class<?> root : CLIENT_REACHABLE_ROOTS) {
            violations.addAll(walkGraphForViolations(root));
        }

        assertThat(violations)
                .as("no client-reachable document DTO component may denote a confidential figure "
                        + "(cost/margin/estimate/price) — the originating object appears only as the opaque "
                        + "sourceRef string, never an embedded estimate/offer DTO (R9.6, R13.3). Violations: %s",
                        violations)
                .isEmpty();
    }

    @Test
    @DisplayName("every declared client-reachable root is a record (so the reflective walk actually inspects it)")
    void everyRootIsARecord() {
        for (Class<?> root : CLIENT_REACHABLE_ROOTS) {
            assertThat(root.isRecord())
                    .as("%s must be a record for the structural walk to inspect its components",
                            root.getSimpleName())
                    .isTrue();
        }
    }

    /**
     * Transitively walks a record's component type graph — the record, its component types, and the
     * generic element types of {@code List<...>} / {@code Collection<...>} components — collecting a
     * violation string for every component whose name contains a confidential whole-word. Enums,
     * {@code java.*}, and non-record leaves are terminal.
     */
    private static List<String> walkGraphForViolations(Class<?> root) {
        List<String> violations = new ArrayList<>();
        Set<Class<?>> visited = new HashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            Class<?> type = queue.poll();
            if (!visited.add(type) || !type.isRecord()) {
                continue;
            }
            for (RecordComponent component : type.getRecordComponents()) {
                String violation = confidentialViolation(type, component.getName());
                if (violation != null) {
                    violations.add(violation);
                }
                enqueueComponentTypes(component, queue);
            }
        }
        return violations;
    }

    /** Returns a human-readable violation string when {@code componentName} contains a confidential whole-word. */
    private static String confidentialViolation(Class<?> owner, String componentName) {
        Set<String> words = splitWords(componentName);
        for (String word : words) {
            if (CONFIDENTIAL_WORDS.contains(word)) {
                return owner.getSimpleName() + "." + componentName + " (confidential word '" + word + "')";
            }
        }
        return null;
    }

    /** Enqueues the record types reachable through a component: its own type and {@code List<X>} element types. */
    private static void enqueueComponentTypes(RecordComponent component, Deque<Class<?>> queue) {
        Class<?> raw = component.getType();
        if (raw.isRecord()) {
            queue.add(raw);
        }
        Type generic = component.getGenericType();
        if (generic instanceof ParameterizedType pt) {
            for (Type arg : pt.getActualTypeArguments()) {
                if (arg instanceof Class<?> argClass && argClass.isRecord()) {
                    queue.add(argClass);
                }
            }
        }
    }

    /**
     * Splits a camelCase / snake_case identifier into its lower-cased constituent words. So
     * {@code unitPrice} → {@code [unit, price]}, {@code content_hash} → {@code [content, hash]},
     * {@code sourceRef} → {@code [source, ref]}.
     */
    private static Set<String> splitWords(String name) {
        String spaced = name
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replace('_', ' ');
        return Arrays.stream(spaced.split("\\s+"))
                .map(w -> w.toLowerCase(Locale.ROOT))
                .filter(w -> !w.isBlank())
                .collect(Collectors.toSet());
    }
}
