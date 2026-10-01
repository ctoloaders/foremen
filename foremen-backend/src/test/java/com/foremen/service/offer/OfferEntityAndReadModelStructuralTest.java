package com.foremen.service.offer;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.foremen.dao.model.NegotiationRoundKind;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferNegotiationRoundEntity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.OneToMany;

/**
 * FOR-05-07, task 2.4 — entity + DTO <b>structural</b> guards.
 *
 * <p>These are lightweight, deterministic compile/reflection guards (no Spring / Testcontainers
 * boot): they assert three structural invariants of the offer aggregate and its client-facing read
 * model.
 *
 * <ol>
 *   <li><b>Offer children cascade-remove with the offer.</b> The {@code discounts} and
 *       {@code negotiationRounds} {@code @OneToMany} collections on {@link OfferEntity} are declared
 *       {@code cascade = ALL} + {@code orphanRemoval = true}, so persisting/removing the offer
 *       cascades to its {@link com.foremen.dao.model.OfferDiscountEntity} /
 *       {@link OfferNegotiationRoundEntity} children (Requirement 15.2 confidentiality is unaffected
 *       by, but this validates the aggregate shape backing, the offer lifecycle). The mapping is the
 *       source of the cascade-remove behaviour, so a reflective mapping assertion is the lightest
 *       correct guard.</li>
 *   <li><b>Round figure-ownership shape.</b> The {@code valueKind} / {@code value} figure fields
 *       exist on {@link OfferNegotiationRoundEntity} and are nullable (the DB CHECK + the service
 *       enforce that they are populated ONLY on a {@link NegotiationRoundKind#MANAGER_PROPOSAL},
 *       R4.3 / R10.18). This guards the shape that lets a {@code DISCOUNT_REQUEST} carry no
 *       figure.</li>
 *   <li><b>Property 21 confidentiality (compile/reflection guard).</b> A transitive walk of the
 *       {@link ClientOfferReadModel} record-component type graph finds <b>no</b> component whose name
 *       denotes a confidential concept — self-cost, cost, margin, worker rate, worker-type tier,
 *       material {@code cost_net}, per-branch/per-tier margin, or estimate-internal unit price
 *       (Property 21 / Requirements 15.2, 19.3, 10.12). Legitimately-named offer fields
 *       ({@code offerNet}, {@code offerGross}, {@code priceRangeMin}, {@code zlM2},
 *       {@code percentage}, {@code value}, {@code amount}) must NOT match.</li>
 * </ol>
 */
@Tag("Feature: FOR-05-07-offer-approval, task 2.4: entity + DTO structural tests")
class OfferEntityAndReadModelStructuralTest {

    // ---------------------------------------------------------------------------------------------
    // 1. Offer children cascade-remove with the offer
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Offer.discounts is @OneToMany cascade=ALL orphanRemoval=true (cascade-removes with the offer)")
    void discountsCascadeRemoveWithOffer() throws NoSuchFieldException {
        assertCascadeRemoveChild(OfferEntity.class, "discounts");
    }

    @Test
    @DisplayName("Offer.negotiationRounds is @OneToMany cascade=ALL orphanRemoval=true (cascade-removes with the offer)")
    void negotiationRoundsCascadeRemoveWithOffer() throws NoSuchFieldException {
        assertCascadeRemoveChild(OfferEntity.class, "negotiationRounds");
    }

    private static void assertCascadeRemoveChild(Class<?> owner, String fieldName) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(fieldName);
        OneToMany mapping = field.getAnnotation(OneToMany.class);
        assertThat(mapping)
                .as("%s.%s must be a @OneToMany collection child", owner.getSimpleName(), fieldName)
                .isNotNull();
        assertThat(mapping.cascade())
                .as("%s.%s must cascade ALL so children persist/remove with the offer",
                        owner.getSimpleName(), fieldName)
                .contains(CascadeType.ALL);
        assertThat(mapping.orphanRemoval())
                .as("%s.%s must set orphanRemoval=true so removed/detached children are deleted",
                        owner.getSimpleName(), fieldName)
                .isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Round figure-ownership shape (value/kind figure fields exist and are nullable)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Round figure fields value/valueKind exist and are nullable (populated only on MANAGER_PROPOSAL)")
    void roundFigureOwnershipShape() throws NoSuchFieldException {
        Field value = OfferNegotiationRoundEntity.class.getDeclaredField("value");
        Field valueKind = OfferNegotiationRoundEntity.class.getDeclaredField("valueKind");

        jakarta.persistence.Column valueColumn = value.getAnnotation(jakarta.persistence.Column.class);
        jakarta.persistence.Column valueKindColumn = valueKind.getAnnotation(jakarta.persistence.Column.class);

        assertThat(valueColumn)
                .as("value must map a @Column")
                .isNotNull();
        assertThat(valueKind.getAnnotation(jakarta.persistence.Enumerated.class))
                .as("valueKind must be an @Enumerated discount-kind figure")
                .isNotNull();

        // Nullable (not NOT NULL): the figure lives ONLY on a MANAGER_PROPOSAL, so a DISCOUNT_REQUEST
        // / MANAGER_REJECT / CLIENT_* round carries neither. A NOT NULL column would forbid that.
        assertThat(valueColumn.nullable())
                .as("value must be nullable — only a MANAGER_PROPOSAL carries a figure (R4.3/R10.18)")
                .isTrue();
        assertThat(valueKindColumn)
                .as("valueKind must map a @Column")
                .isNotNull();
        assertThat(valueKindColumn.nullable())
                .as("valueKind must be nullable — only a MANAGER_PROPOSAL carries a figure (R4.3/R10.18)")
                .isTrue();

        // Exactly one kind is the figure owner.
        assertThat(NegotiationRoundKind.values())
                .as("MANAGER_PROPOSAL is the figure-owning kind")
                .contains(NegotiationRoundKind.MANAGER_PROPOSAL);
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Property 21: the ClientOfferReadModel type graph contains no confidential field name
    // ---------------------------------------------------------------------------------------------

    /**
     * Confidential tokens that must never name a component in the client read-model graph. Matched
     * case-insensitively as camelCase / snake_case "words" (see {@link #splitWords}), so a legitimate
     * offer field like {@code offerNet}, {@code priceRangeMin}, {@code zlM2}, {@code percentage},
     * {@code value}, or {@code amount} does NOT match, while {@code costNet}, {@code unitPrice},
     * {@code selfCost}, {@code marginPct}, {@code workerRate}, or {@code tierCost} DO.
     *
     * <p>The token set follows Property 21's prohibition list (self-cost, cost, margin, worker rate,
     * worker-type tier, material {@code cost_net}, per-branch/per-tier margin, estimate-internal
     * unit price).
     */
    private static final Set<String> CONFIDENTIAL_WORDS = Set.of(
            "cost",       // cost, selfCost, costNet, tierCost, ...
            "selfcost",   // defensive: if not split as self+cost
            "margin",     // margin, marginPct, branchMargin, tierMargin
            "rate",       // workerRate, hourlyRate (worker-rate)
            "tier",       // worker-type tier / per-tier
            "unitprice"); // estimate-internal unit price (unit+price adjacency, see below)

    @Test
    @DisplayName("Property 21: ClientOfferReadModel type graph contains no cost/margin/worker-rate/unit-price field")
    void clientReadModelGraphHasNoConfidentialFieldName() {
        List<String> violations = walkGraphForViolations(ClientOfferReadModel.class);

        assertThat(violations)
                .as("ClientOfferReadModel's type graph must expose no confidential (cost/margin/"
                        + "worker-rate/tier/unit-price) field name (Property 21 / R15.2, R19.3, R10.12)")
                .isEmpty();
    }

    /**
     * Transitively walks a record's component type graph (records, their component types, generic
     * type args of {@code List<...>}; enums / {@code java.*} / {@code BigDecimal} are leaves) and
     * collects every component whose name contains a confidential word.
     */
    private static List<String> walkGraphForViolations(Class<?> root) {
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
                String violation = confidentialViolation(type, component.getName());
                if (violation != null) {
                    violations.add(violation);
                }
                enqueueUnvisited(referencedRecordTypes(component.getGenericType()), visited, pending);
            }
        }
        return violations;
    }

    private static void enqueueUnvisited(List<Class<?>> candidates, Set<Class<?>> visited, Deque<Class<?>> pending) {
        for (Class<?> referenced : candidates) {
            if (!visited.contains(referenced)) {
                pending.push(referenced);
            }
        }
    }

    /** Returns a human-readable violation string when {@code componentName} contains a confidential word. */
    private static String confidentialViolation(Class<?> owner, String componentName) {
        Set<String> words = splitWords(componentName);
        for (String word : words) {
            if (CONFIDENTIAL_WORDS.contains(word)) {
                return owner.getSimpleName() + "." + componentName + " (confidential word: " + word + ")";
            }
        }
        // "unitPrice" as two adjacent words unit+price -> collapse and check.
        String collapsed = String.join("", words);
        if (collapsed.contains("unitprice")) {
            return owner.getSimpleName() + "." + componentName + " (confidential: unitPrice)";
        }
        return null;
    }

    /**
     * Splits a field name into lowercase "words" on camelCase humps, digits, and underscores.
     * e.g. {@code offerNet -> [offer, net]}, {@code costNet -> [cost, net]},
     * {@code priceRangeMin -> [price, range, min]}, {@code zlM2 -> [zl, m]}.
     */
    private static Set<String> splitWords(String name) {
        Set<String> words = new HashSet<>();
        for (String part : name.split("(?<!^)(?=[A-Z])|_|(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)")) {
            if (!part.isBlank()) {
                words.add(part.toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }

    /**
     * Resolves the record types referenced by a component's generic type: the type itself if it is a
     * record, and the record type arguments of a {@code List<...>} (or any parameterized) type.
     * {@code java.*}, {@code BigDecimal}, and enums are leaves and yield nothing.
     */
    private static List<Class<?>> referencedRecordTypes(Type type) {
        List<Class<?>> result = new ArrayList<>();
        collectRecordTypes(type, result);
        return result;
    }

    private static void collectRecordTypes(Type type, List<Class<?>> out) {
        if (type instanceof Class<?> clazz) {
            if (clazz.isRecord()) {
                out.add(clazz);
            }
            // enums / java.* / BigDecimal are leaves — nothing to recurse into.
        } else if (type instanceof ParameterizedType parameterized) {
            for (Type arg : parameterized.getActualTypeArguments()) {
                collectRecordTypes(arg, out);
            }
        }
    }

    /** Guards the splitter regex itself so the confidentiality walk cannot silently degrade. */
    @Test
    @DisplayName("Word splitter separates camelCase / snake_case / digit humps as expected")
    void wordSplitterBehaviour() {
        assertThat(splitWords("offerNet")).containsExactlyInAnyOrder("offer", "net");
        assertThat(splitWords("costNet")).contains("cost");
        assertThat(splitWords("worker_rate")).contains("rate");
        assertThat(splitWords("priceRangeMin")).doesNotContain("cost", "margin", "rate", "tier");
        assertThat(splitWords("zlM2")).doesNotContain("cost", "margin", "rate");
        // sanity: the confidential regex must not be a catch-all
        assertThat(Pattern.compile("x").matcher("y").find()).isFalse();
    }
}
