package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/**
 * FOR-05-02 task 4.5 - permission matrix of the rooms auto-import and room-media endpoints against the
 * seeded {@code ROOMS} grants (changeset 043): MANAGER = CRUD, FOREMAN = READ + UPDATE, WORKER / CLIENT /
 * FINANCIER = READ. Start / cancel / discard resolve to CREATE, polling and draft-media reads to READ,
 * media upload / delete to UPDATE. Project scope: a member of another project reads {@code 404}.
 *
 * <p>Repeatability: generator strategy - per-instance run id; each test uses its own projects.
 *
 * <p>Validates: Requirements 14.1, 14.2, 10.2
 */
class RoomsAutoimportAbacIT extends RoomsAutoimportItBase {

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    @Test
    @DisplayName("start / cancel / discard need ROOMS CREATE: MANAGER and ADMIN allowed, others 403, unauthenticated 401")
    void startCancelDiscardMatrix() throws Exception {
        long projectId = createProject("abac-start");
        for (String role : List.of("MANAGER", "FOREMAN", "WORKER", "CLIENT")) {
            addMember(role, projectId);
        }

        assertThat(status(start(projectId, null, prompt("STUB:THREE_ROOMS")))).isEqualTo(401);
        for (String denied : List.of("FOREMAN", "WORKER", "CLIENT")) {
            assertThat(status(start(projectId, bearer(denied), prompt("STUB:THREE_ROOMS"))))
                    .as("start as " + denied).isEqualTo(403);
        }
        // no job row was created by any denied attempt
        assertThat(status(current(projectId, bearer("ADMIN")))).isEqualTo(204);

        MvcResult started = start(projectId, bearer("MANAGER"), prompt("STUB:THREE_ROOMS STUB:SLOW=2"));
        assertThat(status(started)).isEqualTo(202);
        long jobId = ((Number) JsonPath.read(started.getResponse().getContentAsString(), "$.jobId")).longValue();

        for (String denied : List.of("FOREMAN", "WORKER", "CLIENT")) {
            assertThat(status(cancel(jobId, bearer(denied)))).as("cancel as " + denied).isEqualTo(403);
            assertThat(status(discard(jobId, bearer(denied)))).as("discard as " + denied).isEqualTo(403);
        }
        assertThat(status(getJob(jobId, bearer("ADMIN")))).as("still alive after denied cancels").isEqualTo(200);
        assertThat(status(cancel(jobId, bearer("MANAGER")))).isEqualTo(204);

        long second = startOk(projectId, prompt("STUB:THREE_ROOMS"));
        awaitStatus(second, "COMPLETED");
        assertThat(status(discard(second, bearer("MANAGER")))).isEqualTo(204);
    }

    @Test
    @DisplayName("current / get / draft media need ROOMS READ: every member role reads, a non-member gets 404")
    void readMatrix() throws Exception {
        long projectId = createProject("abac-read");
        for (String role : List.of("MANAGER", "FOREMAN", "WORKER", "CLIENT")) {
            addMember(role, projectId);
        }
        // FINANCIER holds READ but is NOT a member of this project.
        long jobId = startOk(projectId, prompt("STUB:IMAGE_CROPS"), png("files", "plan.png"));
        String done = awaitStatus(jobId, "COMPLETED");
        String mediaId = JsonPath.read(done, "$.result.rooms[0].media[0].mediaId");

        for (String role : List.of("MANAGER", "FOREMAN", "WORKER", "CLIENT")) {
            assertThat(status(current(projectId, bearer(role)))).as("current as " + role).isEqualTo(200);
            assertThat(status(getJob(jobId, bearer(role)))).as("get as " + role).isEqualTo(200);
            assertThat(status(mockMvc.perform(get("/api/rooms/autoimport/" + jobId + "/media/" + mediaId)
                    .header("Authorization", bearer(role))).andReturn())).as("media as " + role).isEqualTo(200);
        }
        assertThat(status(current(projectId, bearer("FINANCIER")))).isEqualTo(404);
        assertThat(status(getJob(jobId, bearer("FINANCIER")))).isEqualTo(404);
        assertThat(status(mockMvc.perform(get("/api/rooms/autoimport/" + jobId + "/media/" + mediaId)
                .header("Authorization", bearer("FINANCIER"))).andReturn())).isEqualTo(404);
        assertThat(status(current(projectId, null))).isEqualTo(401);
    }

    @Test
    @DisplayName("a CREATE-capable member of project A cannot start or cancel in project B (404)")
    void projectScopeOnWrites() throws Exception {
        long projectA = createProject("abac-A");
        long projectB = createProject("abac-B");
        addMember("MANAGER", projectA);

        assertThat(status(start(projectB, bearer("MANAGER"), prompt("STUB:THREE_ROOMS")))).isEqualTo(404);
        assertThat(status(current(projectB, bearer("ADMIN")))).isEqualTo(204);

        long jobB = startOk(projectB, prompt("STUB:THREE_ROOMS"));
        awaitStatus(jobB, "COMPLETED");
        assertThat(status(cancel(jobB, bearer("MANAGER")))).isEqualTo(404);
        assertThat(status(discard(jobB, bearer("MANAGER")))).isEqualTo(404);
        assertThat(status(getJob(jobB, bearer("ADMIN")))).as("foreign cancel/discard are no-ops").isEqualTo(200);
    }

    @Test
    @DisplayName("room media: READ lists, UPDATE uploads (FOREMAN yes; WORKER / CLIENT 403)")
    void mediaMatrix() throws Exception {
        long projectId = createProject("abac-media");
        for (String role : List.of("MANAGER", "FOREMAN", "WORKER", "CLIENT")) {
            addMember(role, projectId);
        }
        long roomId = seedRoom(projectId, "Media room", "9.00");

        for (String role : List.of("MANAGER", "FOREMAN", "WORKER", "CLIENT")) {
            assertThat(status(mockMvc.perform(get("/api/rooms/" + roomId + "/media")
                    .header("Authorization", bearer(role))).andReturn())).as("list as " + role).isEqualTo(200);
        }
        for (String denied : List.of("WORKER", "CLIENT")) {
            var b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .multipart("/api/rooms/" + roomId + "/media").file(png("files", "a.png"))
                    .header("Authorization", bearer(denied));
            assertThat(status(mockMvc.perform(b).andReturn())).as("upload as " + denied).isEqualTo(403);
        }
        for (String allowed : List.of("FOREMAN", "MANAGER", "ADMIN")) {
            var b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .multipart("/api/rooms/" + roomId + "/media").file(png("files", "a.png"))
                    .header("Authorization", bearer(allowed));
            assertThat(status(mockMvc.perform(b).andReturn())).as("upload as " + allowed).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_media WHERE room_id = ?", Integer.class, roomId))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("bulk save with created rooms needs ROOMS CREATE: FOREMAN (UPDATE only) -> 403, MANAGER -> 200")
    void saveWithCreatesMatrix() throws Exception {
        long projectId = createProject("abac-save");
        addMember("MANAGER", projectId);
        addMember("FOREMAN", projectId);
        Map<String, Object> room = new java.util.LinkedHashMap<>();
        room.put("projectId", projectId);
        room.put("roomTypeId", anyRoomTypeId());
        room.put("label", "Created");
        room.put("floorArea", 10);

        assertThat(status(save(projectId, bearer("FOREMAN"), List.of(room)))).isEqualTo(403);
        assertThat(roomDao.findByProjectId(projectId)).isEmpty();
        assertThat(status(save(projectId, bearer("MANAGER"), List.of(room)))).isEqualTo(200);
        assertThat(roomDao.findByProjectId(projectId)).hasSize(1);
    }
}
