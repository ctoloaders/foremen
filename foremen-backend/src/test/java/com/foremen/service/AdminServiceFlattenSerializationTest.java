package com.foremen.service;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.AdminDao;
import com.foremen.dao.ReadOnlyAdminDao;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;

import jakarta.persistence.EntityManager;

/**
 * Focused unit tests for the DEFAULT flat audit serialization added to {@link AdminService}
 * ({@code serializeEntity} flattening + {@code serializeUpdateAfterSnapshot} diff).
 *
 * <p>The flattener and diff helpers are {@code private static} on the interface, so they are
 * exercised through the two public default seams via a minimal {@link AdminService} whose
 * {@code DaoModel} is a plain POJO graph (nested object, object collection, scalar array).
 *
 * <p>Expected flat shape:
 * <ul>
 *   <li>nested object &rarr; {@code owner.name};</li>
 *   <li>object collection &rarr; {@code users[0].name};</li>
 *   <li>scalar array &rarr; {@code tags[0]};</li>
 *   <li>empty object/array leaves are omitted.</li>
 * </ul>
 *
 * Feature: audit flat serialization (global default)
 */
class AdminServiceFlattenSerializationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // --- POJO graph used as the DaoModel ---

    static class Owner {
        public Long id;
        public String name;
        public Owner(Long id, String name) { this.id = id; this.name = name; }
    }

    static class Member {
        public String name;
        public Member(String name) { this.name = name; }
    }

    /** A bean with no serializable properties -> serializes to an empty object {@code {}}. */
    static class Empty {
    }

    static class Root {
        public Long id;
        public Owner owner;
        public List<Member> users;
        public List<String> tags;
        public Empty emptyObject = new Empty();     // serializes to {} -> omitted
        public List<String> emptyList = List.of();  // [] -> omitted

        Root(Long id, Owner owner, List<Member> users, List<String> tags) {
            this.id = id;
            this.owner = owner;
            this.users = users;
            this.tags = tags;
        }
    }

    private AdminService<Object, Object, Root, Long> service() {
        return new AdminService<>() {
            @Override public AdminDao<Root, Long> getDao() { return null; }
            @Override public AuditLogDao getAuditLogDao() { return null; }
            @Override public ServiceToDaoMapper<Root, Object, Object> getMapper() { return null; }
            @Override public ReadOnlyAdminDao<Root, Long> getReadDao() { return null; }
            @Override public EntityManager getEntityManager() { return null; }
        };
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("not valid JSON: " + json, e);
        }
    }

    private Root sample() {
        return new Root(
                7L,
                new Owner(3L, "Alice"),
                List.of(new Member("x"), new Member("y")),
                List.of("a", "b"));
    }

    // --- serializeEntity: flattening ---

    @Test
    @DisplayName("serializeEntity flattens nested object to dot-notation, collections to index notation")
    void flattensNestedAndCollections() {
        JsonNode flat = parse(service().serializeEntity(sample()));

        // scalar leaf
        assertThat(flat.get("id").asLong()).isEqualTo(7L);

        // nested object -> dotted
        assertThat(flat.get("owner.id").asLong()).isEqualTo(3L);
        assertThat(flat.get("owner.name").asText()).isEqualTo("Alice");

        // object collection -> indexed + dotted
        assertThat(flat.get("users[0].name").asText()).isEqualTo("x");
        assertThat(flat.get("users[1].name").asText()).isEqualTo("y");

        // scalar array -> indexed
        assertThat(flat.get("tags[0]").asText()).isEqualTo("a");
        assertThat(flat.get("tags[1]").asText()).isEqualTo("b");

        // the result is single-level: no nested objects/arrays remain
        flat.fields().forEachRemaining(e ->
                assertThat(e.getValue().isContainerNode())
                        .as("key %s should be a scalar leaf", e.getKey())
                        .isFalse());
    }

    @Test
    @DisplayName("serializeEntity omits empty object/array leaves")
    void omitsEmptyContainers() {
        JsonNode flat = parse(service().serializeEntity(sample()));
        assertThat(flat.has("emptyObject")).isFalse();
        assertThat(flat.has("emptyList")).isFalse();
        // and no dotted/indexed descendant of them either
        flat.fieldNames().forEachRemaining(k ->
                assertThat(k).doesNotStartWith("emptyObject").doesNotStartWith("emptyList"));
    }

    @Test
    @DisplayName("serializeEntity returns null for a null entity")
    void nullEntityYieldsNull() {
        assertThat(service().serializeEntity(null)).isNull();
    }

    // --- serializeUpdateAfterSnapshot: diff ---

    @Test
    @DisplayName("update after-snapshot contains ONLY changed keys (dotted/indexed), unchanged omitted")
    void afterSnapshotIsDiffOfChangedKeys() {
        Root before = sample();
        String beforeSnap = service().serializeEntity(before);

        // Change: owner.name Alice->Bob, users[1].name y->z, tags[0] a unchanged, add nothing.
        Root after = new Root(
                7L,
                new Owner(3L, "Bob"),
                List.of(new Member("x"), new Member("z")),
                List.of("a", "b"));

        JsonNode diff = parse(service().serializeUpdateAfterSnapshot(beforeSnap, after));

        // changed keys present with new value
        assertThat(diff.get("owner.name").asText()).isEqualTo("Bob");
        assertThat(diff.get("users[1].name").asText()).isEqualTo("z");

        // unchanged keys omitted
        assertThat(diff.has("id")).isFalse();
        assertThat(diff.has("owner.id")).isFalse();
        assertThat(diff.has("users[0].name")).isFalse();
        assertThat(diff.has("tags[0]")).isFalse();
        assertThat(diff.has("tags[1]")).isFalse();
    }

    @Test
    @DisplayName("update diff records removed keys as null and added keys with their value")
    void diffRecordsRemovedAsNullAndAdded() {
        // before has tags[0],tags[1]; after drops tags to a single element and adds a longer users list
        Root before = new Root(1L, new Owner(1L, "A"),
                List.of(new Member("m0")),
                List.of("a", "b"));
        String beforeSnap = service().serializeEntity(before);

        Root after = new Root(1L, new Owner(1L, "A"),
                List.of(new Member("m0"), new Member("m1")), // users[1].name added
                List.of("a"));                                // tags[1] removed
        JsonNode diff = parse(service().serializeUpdateAfterSnapshot(beforeSnap, after));

        // added key present with value
        assertThat(diff.get("users[1].name").asText()).isEqualTo("m1");
        // removed key present as null
        assertThat(diff.has("tags[1]")).isTrue();
        assertThat(diff.get("tags[1]").isNull()).isTrue();
        // unchanged keys omitted
        assertThat(diff.has("users[0].name")).isFalse();
        assertThat(diff.has("tags[0]")).isFalse();
        assertThat(diff.has("owner.name")).isFalse();
    }

    @Test
    @DisplayName("update after-snapshot falls back to the full flattened after when before is null")
    void nullBeforeFallsBackToFullAfter() {
        JsonNode flat = parse(service().serializeUpdateAfterSnapshot(null, sample()));
        // full flattened after (same as serializeEntity)
        assertThat(flat.get("owner.name").asText()).isEqualTo("Alice");
        assertThat(flat.get("users[0].name").asText()).isEqualTo("x");
        assertThat(flat.get("tags[1]").asText()).isEqualTo("b");
    }
}
