package com.foremen.dao.model;

// Feature: for-05-05b-list-of-materials, Property 4: Reserve map round-trips and tolerates stale keys

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.MaterialsReserveMap.ReserveEntry;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property 4 (FOR-05-05b): the {@link MaterialsReserveMap} round-trips through the JSONB
 * serialization the app uses and tolerates stale keys.
 *
 * <p>The estimate binds {@code materials_reserve_map jsonb} via
 * {@code @JdbcTypeCode(SqlTypes.JSON)}, whose default JSON format mapper is a plain Jackson
 * {@link ObjectMapper} — the same construction the codebase's other JSON round-trip tests use
 * ({@code FormulaParserValidatorRoundTripPropertyTest}, {@code JsonMapConverter}). This property
 * therefore exercises serialize→deserialize with that same mapper and asserts:
 *
 * <ul>
 *   <li>the per-material {@code percent} set survives the round-trip unchanged (including the
 *       {@code Long}-key ⇒ JSON-string-key ⇒ {@code Long}-key transformation Jackson performs), so a
 *       reloaded map yields an equal reserve for every material (R4.2);</li>
 *   <li>a <em>stale</em> {@code materialId} — one absent from a given kosztorys id-set — is ignored
 *       on read without error: looking it up against the live id-set contributes no reserve, exactly
 *       like an absent key (R12.5);</li>
 *   <li>a {@code null} column, an absent key, or a {@code null}/{@code 0} percent all read as
 *       <em>identity</em> (no reserve) for that material (R4.5).</li>
 * </ul>
 *
 * <p>No Spring context and no database — the map is a pure Jackson-serialized model, so the property
 * runs the identical mapper directly.
 *
 * <p>Feature: for-05-05b-list-of-materials, Property 4
 *
 * <p><b>Validates: Requirements 4.2, 12.5</b>
 */
@Tag("Feature: for-05-05b-list-of-materials, Property 4: Reserve map round-trips and tolerates stale keys")
class MaterialsReserveMapPropertyTest {

    /** The same plain mapper Hibernate's default JSON type and {@code JsonMapConverter} use. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Property: the per-material percent set round-trips unchanged through JSONB ---

    @Property(tries = 100)
    void reserveMapRoundTripsPreservingEveryMaterialPercent(
            @ForAll("reserveMaps") MaterialsReserveMap original) throws Exception {

        String json = objectMapper.writeValueAsString(original);
        MaterialsReserveMap reloaded = objectMapper.readValue(json, MaterialsReserveMap.class);

        // The reloaded map holds exactly the same material ids...
        assertThat(reloaded.byMaterialId().keySet())
                .as("round-trip preserves the exact set of material ids")
                .isEqualTo(original.byMaterialId().keySet());

        // ...and an equal reserve percent for each material (the authoritative input, R4.2). The
        // Long key survives Jackson's Long -> JSON string -> Long key transformation.
        for (Map.Entry<Long, ReserveEntry> entry : original.byMaterialId().entrySet()) {
            BigDecimal originalPercent = entry.getValue().percent();
            BigDecimal reloadedPercent = reloaded.byMaterialId().get(entry.getKey()).percent();
            if (originalPercent == null) {
                assertThat(reloadedPercent)
                        .as("null percent stays null (identity) for material %s", entry.getKey())
                        .isNull();
            } else {
                assertThat(reloadedPercent)
                        .as("percent round-trips for material %s", entry.getKey())
                        .isEqualByComparingTo(originalPercent);
            }
        }
    }

    // --- Property: a stale materialId (absent from the kosztorys id-set) reads as identity ---

    @Property(tries = 100)
    void staleMaterialIdIsIgnoredWithoutErrorAndReadsAsIdentity(
            @ForAll("reserveMaps") MaterialsReserveMap original,
            @ForAll("liveIdSets") Set<Long> liveIds) throws Exception {

        // Round-trip through the app's JSON, then read every persisted key against the LIVE id-set.
        String json = objectMapper.writeValueAsString(original);
        MaterialsReserveMap reloaded = objectMapper.readValue(json, MaterialsReserveMap.class);

        // Resolving the effective reserve only for live ids never errors on a stale key, and a stale
        // key contributes exactly the identity reserve (0) — indistinguishable from an absent key.
        for (Long persistedId : reloaded.byMaterialId().keySet()) {
            BigDecimal resolved = resolvePercent(reloaded, persistedId, liveIds);
            if (!liveIds.contains(persistedId)) {
                assertThat(resolved)
                        .as("stale material %s (absent from the kosztorys) reads as identity", persistedId)
                        .isEqualByComparingTo(BigDecimal.ZERO);
            }
        }

        // And a live id that was never in the map also reads as identity (absent key ⇒ no reserve).
        for (Long liveId : liveIds) {
            if (!reloaded.byMaterialId().containsKey(liveId)) {
                assertThat(resolvePercent(reloaded, liveId, liveIds))
                        .as("absent key for live material %s reads as identity", liveId)
                        .isEqualByComparingTo(BigDecimal.ZERO);
            }
        }
    }

    // --- Property: a null column / absent key / null-or-zero percent all read as identity ---

    @Property(tries = 100)
    void nullColumnAbsentKeyAndNullOrZeroPercentAllReadAsIdentity(
            @ForAll("materialIds") Long materialId,
            @ForAll Set<Long> liveIds) {

        // A null column ⇒ empty map ⇒ identity for any material (R4.5).
        assertThat(resolvePercent(null, materialId, liveIds))
                .as("null reserve map reads as identity")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // A map with a null internal map ⇒ identity for any material (defensive null column).
        assertThat(resolvePercent(new MaterialsReserveMap(null), materialId, liveIds))
                .as("null byMaterialId reads as identity")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // An absent key ⇒ identity even when the map holds other materials.
        Map<Long, ReserveEntry> other = new HashMap<>();
        other.put(materialId + 1, new ReserveEntry(new BigDecimal("5.00"), null, null, null));
        MaterialsReserveMap mapWithoutKey = new MaterialsReserveMap(other);
        assertThat(resolvePercent(mapWithoutKey, materialId, Set.of(materialId, materialId + 1)))
                .as("absent key reads as identity")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // A present key whose percent is null or 0 ⇒ identity (R4.5).
        Map<Long, ReserveEntry> nullPercent = new HashMap<>();
        nullPercent.put(materialId, new ReserveEntry(null, null, null, null));
        assertThat(resolvePercent(new MaterialsReserveMap(nullPercent), materialId, Set.of(materialId)))
                .as("null percent reads as identity")
                .isEqualByComparingTo(BigDecimal.ZERO);

        Map<Long, ReserveEntry> zeroPercent = new HashMap<>();
        zeroPercent.put(materialId, new ReserveEntry(BigDecimal.ZERO, null, null, null));
        assertThat(resolvePercent(new MaterialsReserveMap(zeroPercent), materialId, Set.of(materialId)))
                .as("zero percent reads as identity")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * Resolves the effective reserve percent for {@code materialId} given the live kosztorys id-set,
     * mirroring how the read assembler consumes the persisted map: a {@code null} column, a
     * {@code null} internal map, a stale key (absent from {@code liveIds}), an absent key, or a
     * {@code null} percent all resolve to identity ({@code 0}); otherwise the stored percent.
     */
    private static BigDecimal resolvePercent(MaterialsReserveMap map, Long materialId, Set<Long> liveIds) {
        if (map == null || map.byMaterialId() == null || !liveIds.contains(materialId)) {
            return BigDecimal.ZERO;
        }
        ReserveEntry entry = map.byMaterialId().get(materialId);
        if (entry == null || entry.percent() == null) {
            return BigDecimal.ZERO;
        }
        return entry.percent();
    }

    // --- Providers ---

    /** A valid material id (positive Long). */
    @Provide
    Arbitrary<Long> materialIds() {
        return Arbitraries.longs().between(1L, 1_000_000L);
    }

    /**
     * A valid reserve map: 0..8 entries keyed by distinct positive material ids, each entry carrying
     * a valid percent (0..100, ≤2 decimals) or {@code null} (unset ⇒ identity), plus arbitrary
     * computed totals that must survive the round-trip alongside the authoritative percent.
     */
    @Provide
    Arbitrary<MaterialsReserveMap> reserveMaps() {
        Arbitrary<Long> ids = Arbitraries.longs().between(1L, 1_000_000L);
        Arbitrary<ReserveEntry> entries = reserveEntries();
        return Arbitraries.maps(ids, entries).ofMinSize(0).ofMaxSize(8)
                .map(MaterialsReserveMap::new);
    }

    private Arbitrary<ReserveEntry> reserveEntries() {
        Arbitrary<BigDecimal> percent = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("100")).ofScale(2));
        Arbitrary<BigDecimal> total = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("99999.99")).ofScale(2));
        return Combinators.combine(percent, total, total, total).as(ReserveEntry::new);
    }

    /** A set of 0..10 live kosztorys material ids (overlaps with the map ids only by chance). */
    @Provide
    Arbitrary<Set<Long>> liveIdSets() {
        return Arbitraries.longs().between(1L, 1_000_000L)
                .set().ofMinSize(0).ofMaxSize(10);
    }
}
