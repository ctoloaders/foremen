package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/**
 * FOR-05-02 task 4.5 - property-style integration tests over randomly generated reference sets (seeded
 * {@link Random}, so a failure is reproducible from the seed printed in the assertion message). They run
 * against the real database and object store through the real HTTP save endpoint.
 *
 * <ul>
 *   <li><b>Media isolation</b> (design property 11): whatever mix of own / foreign / unknown / malformed
 *       {@code importMedia} references a create carries, exactly the distinct valid media of a job of the
 *       <em>same project</em> that the job result lists is attached (under {@code rooms/{roomId}/});
 *       everything else is reported in {@code skippedMedia}; a foreign job's objects are never copied
 *       nor deleted.</li>
 *   <li><b>Marker integrity</b> (design property 12): {@code aiImported} is {@code true} exactly when the
 *       create carried a valid same-project {@code importJobId}; other ids are reported in {@code
 *       skippedImportRefs}; no update (even one sending {@code aiImported}) and no generic create can set
 *       or clear it.</li>
 * </ul>
 *
 * <p>Repeatability: generator strategy - per-instance run id, fresh projects and jobs per run.
 *
 * <p>Validates: Requirements 9.4, 9.5, 21.1, 21.2, 22.2-22.4
 */
class RoomsAutoimportPropertyIT extends RoomsAutoimportItBase {

    private static final int TRIES = 25;
    private static final long SEED = 20_260_517L;

    private record Fixture(long projectId, long ownJob, List<String> ownMedia, long foreignJob,
                           String foreignMedia, long discardedJob, Map<String, Object> draft) {
    }

    private Fixture fixture() throws Exception {
        long projectId = createProject("prop-own");
        long ownJob = startOk(projectId, prompt("STUB:IMAGE_CROPS"), png("files", "own.png"));
        String ownDone = awaitStatus(ownJob, "COMPLETED");
        List<String> ownMedia = new ArrayList<>();
        ownMedia.add(JsonPath.read(ownDone, "$.result.rooms[0].media[0].mediaId"));
        ownMedia.add(JsonPath.read(ownDone, "$.result.rooms[1].media[0].mediaId"));

        long foreignProject = createProject("prop-foreign");
        long foreignJob = startOk(foreignProject, prompt("STUB:IMAGE_CROPS"), png("files", "foreign.png"));
        String foreignDone = awaitStatus(foreignJob, "COMPLETED");
        String foreignMedia = JsonPath.read(foreignDone, "$.result.rooms[0].media[0].mediaId");

        long discardedProject = createProject("prop-discarded");
        long discardedJob = startOk(discardedProject, prompt("STUB:THREE_ROOMS"));
        awaitStatus(discardedJob, "COMPLETED");
        assertThat(discard(discardedJob, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(204);

        Map<String, Object> draft = draftRoom(ownDone, 0);
        draft.remove("importMedia");
        draft.remove("importJobId");
        draft.put("projectId", projectId);
        return new Fixture(projectId, ownJob, ownMedia, foreignJob, foreignMedia, discardedJob, draft);
    }

    private Map<String, Object> room(Fixture f, int n) {
        Map<String, Object> room = new LinkedHashMap<>(f.draft());
        room.put("label", "Prop room " + n);
        return room;
    }

    @Test
    @DisplayName("media isolation: only distinct valid media of a same-project job is attached; the rest is skipped")
    void mediaIsolationProperty() throws Exception {
        Fixture f = fixture();
        Random random = new Random(SEED);
        String foreignKey = "rooms-import/" + f.foreignJob() + "/media/" + f.foreignMedia();
        assertThat(storage.contains(foreignKey)).isTrue();

        for (int i = 0; i < TRIES; i++) {
            List<Map<String, Object>> refs = new ArrayList<>();
            Set<String> expectedAttached = new LinkedHashSet<>();
            Set<String> expectedSkipped = new LinkedHashSet<>();
            int count = random.nextInt(5);
            for (int k = 0; k < count; k++) {
                Map<String, Object> ref;
                String key;
                boolean valid = false;
                switch (random.nextInt(9)) {
                    case 0, 1 -> {
                        String id = f.ownMedia().get(random.nextInt(f.ownMedia().size()));
                        ref = mediaRef(f.ownJob(), id);
                        key = f.ownJob() + "/" + id;
                        valid = true;
                    }
                    case 2 -> {
                        ref = mediaRef(f.foreignJob(), f.foreignMedia());
                        key = f.foreignJob() + "/" + f.foreignMedia();
                    }
                    case 3 -> {
                        ref = mediaRef(f.foreignJob(), f.ownMedia().get(0));
                        key = f.foreignJob() + "/" + f.ownMedia().get(0);
                    }
                    case 4 -> {
                        ref = mediaRef(f.ownJob(), f.foreignMedia());
                        key = f.ownJob() + "/" + f.foreignMedia();
                    }
                    case 5 -> {
                        ref = mediaRef(987_654_321L, f.ownMedia().get(0));
                        key = "987654321/" + f.ownMedia().get(0);
                    }
                    case 6 -> {
                        ref = mediaRef(f.ownJob(), "../" + f.foreignMedia());
                        key = f.ownJob() + "/../" + f.foreignMedia();
                    }
                    case 7 -> {
                        ref = mediaRef(f.ownJob(), " ");
                        key = f.ownJob() + "/ ";
                    }
                    default -> {
                        ref = mediaRef(f.discardedJob(), f.ownMedia().get(1));
                        key = f.discardedJob() + "/" + f.ownMedia().get(1);
                    }
                }
                refs.add(ref);
                (valid ? expectedAttached : expectedSkipped).add(key);
            }

            Map<String, Object> room = room(f, i);
            room.put("importMedia", refs);
            MvcResult saved = save(f.projectId(), bearer("ADMIN"), List.of(room));
            String msg = "seed=" + SEED + " try=" + i + " refs=" + refs;
            assertThat(saved.getResponse().getStatus()).as(msg + " " + saved.getResponse().getContentAsString())
                    .isEqualTo(200);
            String body = saved.getResponse().getContentAsString();
            long roomId = ((Number) JsonPath.read(body, "$.created[0]")).longValue();

            List<String> uris = jdbc.queryForList("SELECT storage_uri FROM room_media WHERE room_id = ?",
                    String.class, roomId);
            assertThat(uris).as(msg).hasSize(expectedAttached.size());
            assertThat(uris).as(msg).allMatch(u -> u.startsWith("rooms/" + roomId + "/"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_media WHERE room_id = ? AND file_name = ?",
                    Integer.class, roomId, f.foreignMedia())).as(msg + " foreign media leaked").isZero();
            assertThat(JsonPath.<Integer>read(body, "$.skippedMedia.length()")).as(msg)
                    .isEqualTo(expectedSkipped.size());
            // Draft objects are neither copied away nor deleted by a (failed or successful) attach.
            assertThat(storage.contains(foreignKey)).as(msg).isTrue();
            for (String id : f.ownMedia()) {
                assertThat(storage.contains("rooms-import/" + f.ownJob() + "/media/" + id)).as(msg).isTrue();
            }
        }
    }

    @Test
    @DisplayName("marker integrity: aiImported only through a valid same-project importJobId; updates never change it")
    void markerIntegrityProperty() throws Exception {
        Fixture f = fixture();
        Random random = new Random(SEED + 1);
        long unknownJob = 987_654_321L;

        for (int i = 0; i < TRIES; i++) {
            int pick = random.nextInt(5);
            Long jobId = switch (pick) {
                case 0 -> f.ownJob();
                case 1 -> f.foreignJob();
                case 2 -> unknownJob;
                case 3 -> f.discardedJob();
                default -> null;
            };
            boolean valid = pick == 0;

            Map<String, Object> room = room(f, i);
            if (jobId != null) {
                room.put("importJobId", jobId);
            }
            MvcResult saved = save(f.projectId(), bearer("ADMIN"), List.of(room));
            String msg = "seed=" + (SEED + 1) + " try=" + i + " pick=" + pick;
            assertThat(saved.getResponse().getStatus()).as(msg + " " + saved.getResponse().getContentAsString())
                    .isEqualTo(200);
            String body = saved.getResponse().getContentAsString();
            long roomId = ((Number) JsonPath.read(body, "$.created[0]")).longValue();

            assertThat(aiImported(roomId)).as(msg).isEqualTo(valid);
            List<Number> skipped = JsonPath.read(body, "$.skippedImportRefs");
            if (jobId == null || valid) {
                assertThat(skipped).as(msg).isEmpty();
            } else {
                assertThat(skipped.stream().map(Number::longValue)).as(msg).containsExactly(jobId);
            }

            // An update that tries to flip the marker (either way) must not change it.
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("projectId", f.projectId());
            update.put("roomTypeId", room.get("roomTypeId"));
            update.put("label", "Edited " + i);
            update.put("aiImported", !valid);
            String bulk = JsonPath.parse(List.of(Map.of("id", roomId, "data", update))).jsonString();
            MvcResult updated = mockMvc.perform(put("/api/rooms/bulk")
                    .header("Authorization", bearer("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON).content(bulk)).andReturn();
            assertThat(updated.getResponse().getStatus()).as(msg + " " + updated.getResponse().getContentAsString())
                    .isEqualTo(200);
            assertThat(aiImported(roomId)).as(msg + " after update").isEqualTo(valid);
        }
    }

    @Test
    @DisplayName("the generic create endpoint cannot forge the marker, even with aiImported / own importJobId")
    void genericCreateCannotForgeMarker() throws Exception {
        Fixture f = fixture();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", f.projectId());
        body.put("roomTypeId", anyRoomTypeId());
        body.put("label", "Forged");
        body.put("floorArea", 10);
        body.put("aiImported", true);
        body.put("importJobId", f.ownJob());
        MvcResult r = mockMvc.perform(post("/api/rooms").header("Authorization", bearer("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(JsonPath.parse(body).jsonString())).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        long roomId = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
        assertThat(aiImported(roomId)).isFalse();
    }

    private boolean aiImported(long roomId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ai_imported FROM rooms WHERE id = ?",
                Boolean.class, roomId));
    }
}
