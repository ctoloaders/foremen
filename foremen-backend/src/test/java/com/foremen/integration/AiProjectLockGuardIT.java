package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDateTime;
import java.util.ArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.dao.model.RoomsImportStatus;
import com.foremen.dao.model.UserEntity;
import com.jayway.jsonpath.JsonPath;

/**
 * FOR-05-04 task 3.1 - the AI project lock against the real schema: an active {@code ESTIMATE} job row
 * makes every room/estimate/offer write answer {@code 409 error.project.ai.locked}, reads keep working, a
 * {@code ROOMS_ONLY} job locks nothing and the lock ends with a terminal status.
 *
 * <p>Repeatability: generator strategy - every test creates its own project under a per-instance run id.
 *
 * <p>Validates: Requirements 4.1, 4.2, 4.3
 */
class AiProjectLockGuardIT extends RoomsAutoimportItBase {

    @Autowired
    private RoomsImportJobDao jobDao;

    private long job(long projectId, String mode, RoomsImportStatus status) {
        UserEntity admin = user("ADMIN");
        return tx().execute(s -> {
            RoomsImportJobEntity job = new RoomsImportJobEntity();
            job.setProject(entityManager.getReference(ProjectEntity.class, projectId));
            job.setCreator(entityManager.getReference(UserEntity.class, admin.getId()));
            job.setStatus(status);
            job.setMode(mode);
            job.setInputs(new ArrayList<>());
            job.setStartedAt(LocalDateTime.now());
            return jobDao.save(job).getId();
        });
    }

    private void setStatus(long jobId, RoomsImportStatus status) {
        jdbc.update("UPDATE rooms_import_jobs SET status = ? WHERE id = ?", status.name(), jobId);
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).header("Authorization", bearer("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private void assertLocked(MvcResult r, long jobId, String stage) throws Exception {
        String body = r.getResponse().getContentAsString();
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(409);
        assertThat(body).contains("error.project.ai.locked");
        assertThat(JsonPath.<Number>read(body, "$.params.jobId").longValue()).isEqualTo(jobId);
        assertThat(JsonPath.<String>read(body, "$.params.stage")).isEqualTo(stage);
    }

    private void assertNotLocked(MvcResult r) throws Exception {
        assertThat(r.getResponse().getContentAsString()).doesNotContain("error.project.ai.locked");
    }

    private MvcResult upload(long roomId) throws Exception {
        return mockMvc.perform(multipart("/api/rooms/" + roomId + "/media").file(png("files", "a.png"))
                .header("Authorization", bearer("ADMIN"))).andReturn();
    }

    @Test
    @DisplayName("active ESTIMATE job: every write answers 409 error.project.ai.locked, reads work")
    void activeEstimateJobLocksWrites() throws Exception {
        long projectId = createProject("lock");
        long roomId = seedRoom(projectId, "Locked room", "9.00");
        long jobId = job(projectId, "ESTIMATE", RoomsImportStatus.PLANNING_WORKS);

        assertLocked(postJson("/api/rooms/project/" + projectId + "/save", "{\"deleteIds\":[" + roomId + "]}"),
                jobId, "PLANNING_WORKS");
        assertLocked(upload(roomId), jobId, "PLANNING_WORKS");
        assertLocked(postJson("/api/estimates/project/" + projectId + "/assignments", "{\"edits\":[]}"),
                jobId, "PLANNING_WORKS");
        assertLocked(postJson("/api/estimates/project/" + projectId + "/labour-price-adjustment",
                "{\"scope\":\"PROJECT\",\"pct\":5}"), jobId, "PLANNING_WORKS");
        assertLocked(postJson("/api/offers/project/" + projectId + "/prepare", "{}"), jobId, "PLANNING_WORKS");
        assertThat(roomDao.findById(roomId)).isPresent();

        MvcResult media = mockMvc.perform(get("/api/rooms/" + roomId + "/media")
                .header("Authorization", bearer("ADMIN"))).andReturn();
        assertThat(media.getResponse().getStatus()).isEqualTo(200);
        MvcResult matrix = mockMvc.perform(get("/api/estimates/project/" + projectId + "/matrix")
                .header("Authorization", bearer("ADMIN"))).andReturn();
        assertNotLocked(matrix);
    }

    @Test
    @DisplayName("a ROOMS_ONLY job locks nothing")
    void roomsOnlyJobDoesNotLock() throws Exception {
        long projectId = createProject("rooms-only");
        long roomId = seedRoom(projectId, "Free room", "9.00");
        job(projectId, "ROOMS_ONLY", RoomsImportStatus.MEASURING);

        assertNotLocked(postJson("/api/rooms/project/" + projectId + "/save", "{}"));
        assertThat(upload(roomId).getResponse().getStatus()).isEqualTo(200);
        assertNotLocked(postJson("/api/estimates/project/" + projectId + "/assignments", "{\"edits\":[]}"));
        assertNotLocked(postJson("/api/offers/project/" + projectId + "/prepare", "{}"));
    }

    @Test
    @DisplayName("the lock ends with a terminal status")
    void lockEndsWithTerminalStatus() throws Exception {
        long projectId = createProject("terminal");
        long roomId = seedRoom(projectId, "Room", "9.00");
        long jobId = job(projectId, "ESTIMATE", RoomsImportStatus.ASSIGNING);

        for (RoomsImportStatus terminal : new RoomsImportStatus[] {RoomsImportStatus.COMPLETED,
                RoomsImportStatus.FAILED}) {
            setStatus(jobId, RoomsImportStatus.ASSIGNING);
            assertLocked(upload(roomId), jobId, "ASSIGNING");
            setStatus(jobId, terminal);
            assertThat(upload(roomId).getResponse().getStatus()).as(terminal.name()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("deleting the job row lifts the lock")
    void deletedJobLiftsLock() throws Exception {
        long projectId = createProject("deleted");
        long roomId = seedRoom(projectId, "Room", "9.00");
        long jobId = job(projectId, "ESTIMATE", RoomsImportStatus.SAVING_ROOMS);
        assertLocked(upload(roomId), jobId, "SAVING_ROOMS");
        jdbc.update("DELETE FROM rooms_import_jobs WHERE id = ?", jobId);
        assertThat(upload(roomId).getResponse().getStatus()).isEqualTo(200);
    }
}
