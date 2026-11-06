package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * FOR-05-02 task 4.5 - with {@code foremen.rooms-import.enabled=false} (the default of the product) the
 * start endpoint answers {@code 503 error.roomsimport.unavailable}, creates no job row and the rest of
 * the Rooms API stays usable.
 *
 * <p>Repeatability: generator strategy - per-instance run id and a fresh project per test.
 *
 * <p>Validates: Requirements 1.6, 13.4
 */
@TestPropertySource(properties = {
        "foremen.rooms-import.enabled=false",
        "foremen.rooms-import.llm.stub=false"
})
class RoomsAutoimportDisabledIT extends RoomsAutoimportItBase {

    @Test
    @DisplayName("disabled feature -> 503, no job row, current endpoint still answers 204")
    void startIsUnavailable() throws Exception {
        long projectId = createProject("disabled");

        MvcResult r = start(projectId, bearer("ADMIN"), prompt("STUB:THREE_ROOMS"));
        assertThat(r.getResponse().getStatus()).isEqualTo(503);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rooms_import_jobs WHERE project_id = ?",
                Integer.class, projectId)).isZero();
        assertThat(current(projectId, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(204);
        assertThat(AI_CALLS.stream().filter(c -> c.contains("run-" + runId))).isEmpty();
    }

    @Test
    @DisplayName("disabled feature leaves manual room creation and media untouched")
    void manualRoomsStillWork() throws Exception {
        long projectId = createProject("disabled-manual");
        long roomId = seedRoom(projectId, "Manual", "8.00");
        assertThat(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/rooms/" + roomId + "/media").header("Authorization", bearer("ADMIN")))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
