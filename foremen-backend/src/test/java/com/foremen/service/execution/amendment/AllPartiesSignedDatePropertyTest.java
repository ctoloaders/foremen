package com.foremen.service.execution.amendment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.foremen.dao.model.DocumentSignatureEntity;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * FOR-06-09 (Requirement 3.1) — property-based coverage of the pure, persistence-free
 * {@link AllPartiesSignedDateResolver}, which derives an amendment's all-parties-signed date as the
 * maximum {@code signedAt} across the linked document's signatures. The resolver holds no state and
 * does no I/O, so it is exercised in isolation with no Spring / persistence context (design
 * §{@code AllPartiesSignedDateResolver}).
 *
 * <p><b>Property 7: The effective date is the latest signature completion time.</b>
 * <ul>
 *   <li>{@code resolve(S) == max(signedAt).toLocalDate()} over a non-empty, all-signed set.</li>
 *   <li>Order-insensitive: {@code resolve(S) == resolve(permute(S))}.</li>
 * </ul>
 *
 * <p>Feature: FOR-06-09-amendment-signing, Property 7
 *
 * <p><b>Validates: Requirements 3.1</b>
 */
@Tag("Feature: FOR-06-09-amendment-signing, Property 7")
class AllPartiesSignedDatePropertyTest {

    private final AllPartiesSignedDateResolver resolver = new AllPartiesSignedDateResolver();

    // --- Property 7a: resolve == max(signedAt).toLocalDate() ------------------------------------

    /**
     * // Feature: FOR-06-09-amendment-signing, Property 7
     *
     * <p>For any non-empty signature set with every {@code signedAt} set, {@code resolve} returns the
     * calendar date of the latest signature completion — {@code max(signedAt).toLocalDate()}
     * (Requirement 3.1).
     */
    @Property(tries = 300)
    @Tag("Feature: FOR-06-09-amendment-signing, Property 7")
    void resolveReturnsLatestSignatureDate(
            @ForAll("signedAtLists") List<LocalDateTime> signedAtTimes) {

        List<DocumentSignatureEntity> signatures = signaturesFor(signedAtTimes);

        LocalDate expected = signedAtTimes.stream()
                .max(LocalDateTime::compareTo)
                .orElseThrow()
                .toLocalDate();

        assertThat(resolver.resolve(signatures)).isEqualTo(expected);
    }

    // --- Property 7b: order-insensitivity -------------------------------------------------------

    /**
     * // Feature: FOR-06-09-amendment-signing, Property 7
     *
     * <p>The maximum is order-insensitive: shuffling the signatures yields the same resolved date
     * ({@code resolve(S) == resolve(permute(S))}) (Requirement 3.1).
     */
    @Property(tries = 300)
    @Tag("Feature: FOR-06-09-amendment-signing, Property 7")
    void resolveIsOrderInsensitive(
            @ForAll("signedAtLists") List<LocalDateTime> signedAtTimes,
            @ForAll long seed) {

        List<DocumentSignatureEntity> signatures = signaturesFor(signedAtTimes);
        List<DocumentSignatureEntity> permuted = new ArrayList<>(signatures);
        Collections.shuffle(permuted, new java.util.Random(seed));

        assertThat(resolver.resolve(permuted)).isEqualTo(resolver.resolve(signatures));
    }

    // --- Generators -----------------------------------------------------------------------------

    /**
     * Signature completion timestamps within a wide calendar window. Minute precision keeps the
     * generated instants readable while still spanning many distinct dates so ties and same-day
     * clusters both occur.
     */
    @Provide
    Arbitrary<List<LocalDateTime>> signedAtLists() {
        long min = LocalDateTime.of(2000, 1, 1, 0, 0).toEpochSecond(ZoneOffset.UTC);
        long max = LocalDateTime.of(2100, 12, 31, 23, 59).toEpochSecond(ZoneOffset.UTC);
        Arbitrary<LocalDateTime> element = Arbitraries.longs()
                .between(min, max)
                .map(epochSecond ->
                        LocalDateTime.ofEpochSecond(epochSecond, 0, ZoneOffset.UTC));
        return element.list().ofMinSize(1).ofMaxSize(8);
    }

    // --- Signature builder ----------------------------------------------------------------------

    private List<DocumentSignatureEntity> signaturesFor(List<LocalDateTime> signedAtTimes) {
        List<DocumentSignatureEntity> signatures = new ArrayList<>(signedAtTimes.size());
        for (LocalDateTime signedAt : signedAtTimes) {
            DocumentSignatureEntity signature = new DocumentSignatureEntity();
            signature.setSignedAt(signedAt);
            signatures.add(signature);
        }
        return signatures;
    }
}
