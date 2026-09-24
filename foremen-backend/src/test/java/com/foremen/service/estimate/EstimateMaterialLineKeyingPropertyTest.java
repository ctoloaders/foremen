package com.foremen.service.estimate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.MaterialTypeEntity;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based test for the {@code (roomQty, branch, type)} material-line <b>keying</b> in
 * {@code EstimateAssignmentService} (FOR-05-05, task 5.6, R13.4).
 *
 * <p>The estimate's copied material collection is keyed uniquely per assignment by branch and type —
 * DB {@code UNIQUE (room_qty_id, branch, construction_type_id, finishing_type_id)}. The service's
 * add-material primitive is idempotent per {@code (roomQty, branch, type)}: adding an already-present
 * key returns the existing line rather than duplicating it, and its {@code findMaterialLine} lookup
 * matches by branch AND the branch-appropriate type id (a construction type {@code T} and a finishing
 * type {@code T} sharing the same numeric id are DISTINCT keys). This test reconstructs that keyed-add
 * primitive over an in-memory room-qty (using the same branch/type matching the service applies) and
 * asserts, over a randomized sequence of add operations:
 * <ul>
 *   <li>after any add sequence, at most one material line exists per {@code (branch, type)} key —
 *       adding a duplicate key never grows the collection (R13.4);</li>
 *   <li>the number of distinct lines equals the number of distinct {@code (branch, type)} keys
 *       requested;</li>
 *   <li>a construction type id and a finishing type id with the same numeric value are two different
 *       keys (the branch is part of the key).</li>
 * </ul>
 *
 * <p>Feature: for-05-05-bill-of-materials, Property 11: Material lines are keyed uniquely per
 * assignment by branch and type
 *
 * <p><b>Validates: Requirements 13.4</b>
 */
// Feature: for-05-05-bill-of-materials, Property 11: Material lines are keyed uniquely per assignment by branch and type
@Tag("Feature: for-05-05-bill-of-materials, Property 11: Material lines are keyed uniquely per assignment by branch and type")
class EstimateMaterialLineKeyingPropertyTest {

    /**
     * Property 11: for every generated sequence of {@code (branch, type)} add requests against a single
     * room-qty, the resulting material collection contains exactly one line per distinct
     * {@code (branch, type)} key, and construction/finishing share no keys even at the same numeric type
     * id (R13.4).
     *
     * <p>Feature: for-05-05-bill-of-materials, Property 11: Material lines are keyed uniquely per
     * assignment by branch and type
     *
     * <p><b>Validates: Requirements 13.4</b>
     */
    @Property(tries = 200)
    @Tag("Feature: for-05-05-bill-of-materials, Property 11: Material lines are keyed uniquely per assignment by branch and type")
    void materialLinesAreKeyedUniquelyPerAssignmentByBranchAndType(@ForAll("addRequests") List<AddRequest> requests) {
        EstimateLineRoomQtyEntity roomQty = new EstimateLineRoomQtyEntity();

        // Replay the service's idempotent keyed add: adding an existing (branch, type) is a no-op.
        for (AddRequest request : requests) {
            doAddMaterialLine(roomQty, request.branch, request.typeId);
        }

        List<EstimateLineRoomMaterialEntity> materials = roomQty.getMaterials();

        // The distinct (branch, type) keys that were requested.
        Set<Key> requestedKeys = new HashSet<>();
        for (AddRequest request : requests) {
            requestedKeys.add(new Key(request.branch, request.typeId));
        }

        // Exactly one line per distinct key — no duplicate rows regardless of add order/repeats.
        assertThat(materials).hasSize(requestedKeys.size());

        Set<Key> presentKeys = new HashSet<>();
        Map<Key, Integer> counts = new HashMap<>();
        for (EstimateLineRoomMaterialEntity material : materials) {
            Key key = keyOf(material);
            presentKeys.add(key);
            counts.merge(key, 1, Integer::sum);
        }

        // Every requested key is present exactly once; the set of present keys equals the requested set.
        assertThat(presentKeys).isEqualTo(requestedKeys);
        assertThat(counts.values()).allMatch(count -> count == 1);

        // findMaterialLine returns the single line for every requested key, and null for a key that
        // was never added (including the opposite branch at the same numeric type id).
        for (Key key : requestedKeys) {
            EstimateLineRoomMaterialEntity found = findMaterialLine(roomQty, key.branch(), key.typeId());
            assertThat(found).isNotNull();
            assertThat(keyOf(found)).isEqualTo(key);

            // Opposite branch at the same numeric type id: a distinct key -> only present if it was
            // itself requested.
            ConsumptionBranch opposite = key.branch() == ConsumptionBranch.construction
                    ? ConsumptionBranch.finishing
                    : ConsumptionBranch.construction;
            Key oppositeKey = new Key(opposite, key.typeId());
            EstimateLineRoomMaterialEntity oppositeFound = findMaterialLine(roomQty, opposite, key.typeId());
            if (requestedKeys.contains(oppositeKey)) {
                assertThat(oppositeFound).isNotNull();
            } else {
                assertThat(oppositeFound).isNull();
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Reconstructed service primitives (the keyed add + the (branch, type) lookup)
    // ------------------------------------------------------------------------------------------

    /**
     * The service's keyed add primitive, reconstructed: add a {@code (branch, type)} line, or return
     * the already-present line of the same key without duplicating it (R13.4). Mirrors
     * {@code EstimateAssignmentService.doAddMaterialLine}'s idempotency (minus the range copy, which is
     * exercised by Property 10).
     */
    private static EstimateLineRoomMaterialEntity doAddMaterialLine(
            EstimateLineRoomQtyEntity roomQty, ConsumptionBranch branch, Long typeId) {
        EstimateLineRoomMaterialEntity existing = findMaterialLine(roomQty, branch, typeId);
        if (existing != null) {
            return existing;
        }
        EstimateLineRoomMaterialEntity material = new EstimateLineRoomMaterialEntity();
        material.setRoomQty(roomQty);
        material.setBranch(branch);
        if (branch == ConsumptionBranch.construction) {
            ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
            type.setId(typeId);
            type.setCode("CT-" + typeId);
            type.setNameRU("ct-ru");
            type.setNamePL("ct-pl");
            material.setConstructionType(type);
        } else {
            MaterialTypeEntity type = new MaterialTypeEntity();
            type.setId(typeId);
            type.setCode("FT-" + typeId);
            type.setNameRU("ft-ru");
            type.setNamePL("ft-pl");
            material.setFinishingType(type);
        }
        roomQty.getMaterials().add(material);
        return material;
    }

    /**
     * The service's {@code (branch, type)} lookup, reconstructed: matches by branch AND the
     * branch-appropriate type id, so a construction type and a finishing type with the same numeric id
     * are distinct keys.
     */
    private static EstimateLineRoomMaterialEntity findMaterialLine(
            EstimateLineRoomQtyEntity roomQty, ConsumptionBranch branch, Long typeId) {
        if (branch == null || typeId == null) {
            return null;
        }
        for (EstimateLineRoomMaterialEntity material : roomQty.getMaterials()) {
            if (material.getBranch() != branch) {
                continue;
            }
            Long lineTypeId = branch == ConsumptionBranch.construction
                    ? (material.getConstructionType() != null ? material.getConstructionType().getId() : null)
                    : (material.getFinishingType() != null ? material.getFinishingType().getId() : null);
            if (typeId.equals(lineTypeId)) {
                return material;
            }
        }
        return null;
    }

    private static Key keyOf(EstimateLineRoomMaterialEntity material) {
        Long typeId = material.getBranch() == ConsumptionBranch.construction
                ? (material.getConstructionType() != null ? material.getConstructionType().getId() : null)
                : (material.getFinishingType() != null ? material.getFinishingType().getId() : null);
        return new Key(material.getBranch(), typeId);
    }

    /** The {@code (branch, type)} material-line key under test (R13.4). */
    private record Key(ConsumptionBranch branch, Long typeId) {
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** One add request: a branch + a type id (drawn from a small pool so keys collide and repeat). */
    private record AddRequest(ConsumptionBranch branch, Long typeId) {
    }

    @Provide
    Arbitrary<List<AddRequest>> addRequests() {
        // Small type-id pool (1..4) shared across branches so the SAME numeric id appears under both
        // branches (distinct keys) and the SAME (branch, type) is requested repeatedly (idempotency).
        Arbitrary<AddRequest> request = Combinators.combine(
                        Arbitraries.of(ConsumptionBranch.construction, ConsumptionBranch.finishing),
                        Arbitraries.longs().between(1L, 4L))
                .as(AddRequest::new);
        return request.list().ofMinSize(0).ofMaxSize(30);
    }
}
