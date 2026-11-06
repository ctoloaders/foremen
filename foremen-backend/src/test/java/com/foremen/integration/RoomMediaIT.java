package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/**
 * FOR-05-02 task 4.5 - room media CRUD and cascade against the real schema (changeset 214, FK
 * {@code ON DELETE CASCADE}), including project isolation and the grouped {@code mediaCount}.
 *
 * <p>Repeatability: generator strategy - each test creates its own project and room under a per-instance
 * run id.
 *
 * <p>Validates: Requirements 10.2, 14.1, 14.2
 */
class RoomMediaIT extends RoomsAutoimportItBase {

    private MvcResult upload(long roomId, String bearer, MockMultipartFile... files) throws Exception {
        var b = multipart("/api/rooms/" + roomId + "/media").header("Authorization", bearer);
        for (MockMultipartFile f : files) {
            b.file(f);
        }
        return mockMvc.perform(b).andReturn();
    }

    private MvcResult list(long roomId, String bearer) throws Exception {
        return mockMvc.perform(get("/api/rooms/" + roomId + "/media").header("Authorization", bearer)).andReturn();
    }

    @Test
    @DisplayName("upload -> list -> content -> mediaCount -> delete round trip")
    void crudRoundTrip() throws Exception {
        long projectId = createProject("media");
        long roomId = seedRoom(projectId, "With media", "9.00");

        assertThat(JsonPath.<Integer>read(list(roomId, bearer("ADMIN")).getResponse().getContentAsString(),
                "$.length()")).isZero();

        MvcResult up = upload(roomId, bearer("ADMIN"), png("files", "front.png"));
        assertThat(up.getResponse().getStatus()).as(up.getResponse().getContentAsString()).isEqualTo(200);
        String listed = list(roomId, bearer("ADMIN")).getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(listed, "$.length()")).isEqualTo(1);
        assertThat(JsonPath.<String>read(listed, "$[0].kind")).isEqualTo("UPLOAD");
        assertThat(JsonPath.<String>read(listed, "$[0].fileName")).isEqualTo("front.png");
        long mediaId = ((Number) JsonPath.read(listed, "$[0].id")).longValue();

        MvcResult content = mockMvc.perform(get("/api/rooms/" + roomId + "/media/" + mediaId + "/content")
                .header("Authorization", bearer("ADMIN"))).andReturn();
        assertThat(content.getResponse().getStatus()).isEqualTo(200);
        assertThat(content.getResponse().getContentAsByteArray()).isEqualTo(pngBytes());
        assertThat(content.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");

        // The grouped count is exposed on the list endpoint.
        MvcResult pageResult = mockMvc.perform(get("/api/rooms").param("size", "100")
                .param("query", "project.id==" + projectId).header("Authorization", bearer("ADMIN")))
                .andReturn();
        assertThat(pageResult.getResponse().getStatus()).as(pageResult.getResponse().getContentAsString())
                .isEqualTo(200);
        String page = pageResult.getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(page, "$.content[?(@.id == " + roomId + ")].mediaCount"))
                .containsExactly(1);

        String storageUri = jdbc.queryForObject("SELECT storage_uri FROM room_media WHERE id = ?",
                String.class, mediaId);
        assertThat(storage.contains(storageUri)).isTrue();

        assertThat(mockMvc.perform(delete("/api/rooms/" + roomId + "/media/" + mediaId)
                .header("Authorization", bearer("ADMIN"))).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(JsonPath.<Integer>read(list(roomId, bearer("ADMIN")).getResponse().getContentAsString(),
                "$.length()")).isZero();
        await("stored object removed", () -> !storage.contains(storageUri), 5_000);
        // deleting again is a 404
        assertThat(mockMvc.perform(delete("/api/rooms/" + roomId + "/media/" + mediaId)
                .header("Authorization", bearer("ADMIN"))).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("invalid uploads are rejected: wrong type, spoofed content, empty request")
    void invalidUploadsRejected() throws Exception {
        long roomId = seedRoom(createProject("media-invalid"), "Invalid", "9.00");

        MvcResult text = upload(roomId, bearer("ADMIN"),
                new MockMultipartFile("files", "x.txt", "text/plain", "hello".getBytes()));
        assertThat(text.getResponse().getStatus()).isEqualTo(400);
        assertThat(text.getResponse().getContentAsString()).contains("error.room.media.type");

        MvcResult spoofed = upload(roomId, bearer("ADMIN"),
                new MockMultipartFile("files", "x.png", "image/png", "not really a png".getBytes()));
        assertThat(spoofed.getResponse().getStatus()).isEqualTo(400);
        assertThat(spoofed.getResponse().getContentAsString()).contains("error.room.media.type");

        assertThat(JsonPath.<Integer>read(list(roomId, bearer("ADMIN")).getResponse().getContentAsString(),
                "$.length()")).as("a rejected request stores nothing").isZero();
    }

    @Test
    @DisplayName("deleting a room cascades its media rows and removes the stored objects")
    void roomDeleteCascadesMedia() throws Exception {
        long roomId = seedRoom(createProject("media-cascade"), "Cascade", "9.00");
        assertThat(upload(roomId, bearer("ADMIN"), png("files", "a.png"), png("files", "b.png"))
                .getResponse().getStatus()).isEqualTo(200);
        List<String> uris = jdbc.queryForList("SELECT storage_uri FROM room_media WHERE room_id = ?",
                String.class, roomId);
        assertThat(uris).hasSize(2);

        MvcResult del = mockMvc.perform(delete("/api/rooms/" + roomId).header("Authorization", bearer("ADMIN")))
                .andReturn();
        assertThat(del.getResponse().getStatus()).isIn(200, 204);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_media WHERE room_id = ?", Integer.class, roomId))
                .isZero();
        await("stored objects removed", () -> uris.stream().noneMatch(storage::contains), 5_000);
    }

    @Test
    @DisplayName("a member of another project gets 404 for the room's media (no existence leak)")
    void foreignProjectIsolation() throws Exception {
        long roomId = seedRoom(createProject("media-own"), "Own", "9.00");
        long otherProject = createProject("media-other");
        addMember("MANAGER", otherProject);

        assertThat(list(roomId, bearer("MANAGER")).getResponse().getStatus()).isEqualTo(404);
        assertThat(upload(roomId, bearer("MANAGER"), png("files", "a.png")).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_media WHERE room_id = ?", Integer.class, roomId))
                .isZero();
    }
}
