package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.NotificationEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.dao.model.RoomsImportStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.roomsimport.RoomsImportSweepService;
import com.foremen.service.roomsimport.RoomsImportJobTerminator;
import com.jayway.jsonpath.JsonPath;

/**
 * FOR-05-02 task 4.5 - end-to-end flow of the rooms auto-import against the real schema and the
 * deterministic stub client: start, poll, result, bulk save with {@code importMedia}/{@code importJobId},
 * stale and foreign references, the notification contract, the cancel endpoint, the single-job rule
 * (concurrent starts, terminal replacement), the {@code current} endpoint states, the {@code aiImported}
 * marker and filter, and the append-only guarantee.
 *
 * <p>Repeatability: generator strategy - every project, user and prompt carries a per-instance run id.
 *
 * <p>Validates: Requirements 1.6, 1.8, 2.1, 2.5, 2.6, 9.4, 9.5, 16.4, 19.1-19.4, 21.1, 21.2, 22.2-22.4,
 * 17.1-17.6
 */
class RoomsAutoimportFlowIT extends RoomsAutoimportItBase {

    @Autowired private RoomsImportJobDao jobDao;
    @Autowired private RoomsImportSweepService sweepService;
    @Autowired private RoomsImportJobTerminator terminator;

    // ------------------------------------------------------------------ happy path + save

    @Test
    @DisplayName("start -> poll -> COMPLETED result -> notification -> bulk save with importMedia/importJobId")
    void startPollResultSave() throws Exception {
        long projectId = createProject("flow");
        long existingRoom = seedRoom(projectId, "Existing", "10.00");

        long jobId = startOk(projectId, prompt("STUB:IMAGE_CROPS"), png("files", "plan.png"));
        String done = awaitStatus(jobId, "COMPLETED");

        assertThat(JsonPath.<Integer>read(done, "$.result.rooms.length()")).isEqualTo(2);
        String mediaId = JsonPath.read(done, "$.result.rooms[0].media[0].mediaId");
        assertThat(mediaId).endsWith(".png");

        // R17.1/17.3/17.4: exactly one completed notification for the starter, Rooms deep-link, no body.
        awaitNotifications("ADMIN", "notification.roomsImport.completed", 1);
        List<NotificationEntity> completed = notifications("ADMIN", "notification.roomsImport.completed");
        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).getDeepLink()).isEqualTo("/projects/" + projectId + "/rooms");
        assertThat(completed.get(0).getBody()).isNull();
        assertThat(notifications("ADMIN", "notification.roomsImport.failed")).isEmpty();

        // The draft media is readable until it is attached.
        MvcResult draft = mockMvc.perform(get("/api/rooms/autoimport/" + jobId + "/media/" + mediaId)
                .header("Authorization", bearer("ADMIN"))).andReturn();
        assertThat(draft.getResponse().getStatus()).isEqualTo(200);
        assertThat(draft.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(done).as("not applied yet (appliedAt is omitted)").doesNotContain("appliedAt");

        // Save both draft rooms; the first one carries its crop.
        Map<String, Object> r1 = draftRoom(done, 0);
        r1.put("projectId", projectId);
        r1.put("importJobId", jobId);
        r1.put("importMedia", List.of(mediaRef(jobId, mediaId)));
        Map<String, Object> r2 = draftRoom(done, 1);
        r2.put("projectId", projectId);
        r2.put("importJobId", jobId);

        MvcResult saved = save(projectId, bearer("ADMIN"), List.of(r1, r2));
        assertThat(saved.getResponse().getStatus()).as(saved.getResponse().getContentAsString()).isEqualTo(200);
        String body = saved.getResponse().getContentAsString();
        List<Integer> created = JsonPath.read(body, "$.created");
        assertThat(created).hasSize(2);
        assertThat(JsonPath.<List<?>>read(body, "$.skippedMedia")).isEmpty();
        assertThat(JsonPath.<List<?>>read(body, "$.skippedImportRefs")).isEmpty();

        // aiImported marker (22.3), job applied (22.2), crop attached as SOURCE_CROP (9.4).
        for (Integer id : created) {
            MvcResult room = mockMvc.perform(get("/api/rooms/" + id)
                    .header("Authorization", bearer("ADMIN"))).andReturn();
            assertThat(JsonPath.<Boolean>read(room.getResponse().getContentAsString(), "$.aiImported")).isTrue();
        }
        assertThat(JsonPath.<String>read(getJob(jobId, bearer("ADMIN")).getResponse().getContentAsString(),
                "$.appliedAt")).isNotBlank();
        MvcResult media = mockMvc.perform(get("/api/rooms/" + created.get(0) + "/media")
                .header("Authorization", bearer("ADMIN"))).andReturn();
        assertThat(JsonPath.<String>read(media.getResponse().getContentAsString(), "$[0].kind"))
                .isEqualTo("SOURCE_CROP");
        assertThat(JsonPath.<Integer>read(media.getResponse().getContentAsString(), "$.length()")).isEqualTo(1);

        // Filter by the marker (22.4): both imported rooms, never the pre-existing one.
        MvcResult filtered = mockMvc.perform(get("/api/rooms")
                .param("size", "100")
                .param("query", "project.id==" + projectId + " AND aiImported==true")
                .header("Authorization", bearer("ADMIN"))).andReturn();
        assertThat(filtered.getResponse().getStatus()).as(filtered.getResponse().getContentAsString())
                .isEqualTo(200);
        String filteredBody = filtered.getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(filteredBody, "$.content[*].id"))
                .containsExactlyInAnyOrderElementsOf(created).doesNotContain((int) existingRoom);

        // Append-only (21.1, 21.2): the pre-existing room is untouched, nothing was deleted.
        RoomsAppendOnlySnapshot after = snapshot(existingRoom);
        assertThat(after.label()).isEqualTo("Existing");
        assertThat(after.floorArea()).isEqualByComparingTo("10.00");
        assertThat(after.aiImported()).isFalse();
        assertThat(roomDao.findByProjectId(projectId)).hasSize(3);
    }

    private record RoomsAppendOnlySnapshot(String label, java.math.BigDecimal floorArea, boolean aiImported) {
    }

    private RoomsAppendOnlySnapshot snapshot(long roomId) {
        return tx().execute(s -> {
            var r = roomDao.findById(roomId).orElseThrow();
            return new RoomsAppendOnlySnapshot(r.getLabel(), r.getFloorArea(), r.isAiImported());
        });
    }

    // ------------------------------------------------------------------ stale / foreign references

    @Test
    @DisplayName("foreign-project, unknown and discarded job/media refs are skipped softly; rooms stay unmarked")
    void staleAndForeignReferences() throws Exception {
        long projectA = createProject("A");
        long projectB = createProject("B");

        long foreignJob = startOk(projectB, prompt("STUB:IMAGE_CROPS"), png("files", "b.png"));
        String foreignDone = awaitStatus(foreignJob, "COMPLETED");
        String foreignMedia = JsonPath.read(foreignDone, "$.result.rooms[0].media[0].mediaId");

        long ownJob = startOk(projectA, prompt("STUB:THREE_ROOMS"));
        String ownDone = awaitStatus(ownJob, "COMPLETED");
        long staleJob = startOk(createProject("C"), prompt("STUB:THREE_ROOMS"));
        awaitStatus(staleJob, "COMPLETED");
        assertThat(discard(staleJob, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(204);

        Map<String, Object> foreign = draftRoom(ownDone, 0);
        foreign.put("projectId", projectA);
        foreign.put("importJobId", foreignJob);
        foreign.put("importMedia", List.of(mediaRef(foreignJob, foreignMedia)));
        Map<String, Object> unknown = draftRoom(ownDone, 1);
        unknown.put("projectId", projectA);
        unknown.put("importJobId", 987_654_321L);
        unknown.put("importMedia", List.of(mediaRef(987_654_321L, "nope.png")));
        Map<String, Object> stale = draftRoom(ownDone, 2);
        stale.put("projectId", projectA);
        stale.put("importJobId", staleJob);

        MvcResult saved = save(projectA, bearer("ADMIN"), List.of(foreign, unknown, stale));
        assertThat(saved.getResponse().getStatus()).as(saved.getResponse().getContentAsString()).isEqualTo(200);
        String body = saved.getResponse().getContentAsString();
        List<Integer> created = JsonPath.read(body, "$.created");
        assertThat(created).hasSize(3);
        assertThat(JsonPath.<List<Number>>read(body, "$.skippedImportRefs").stream().map(Number::longValue))
                .containsExactlyInAnyOrder(foreignJob, 987_654_321L, staleJob);
        assertThat(JsonPath.<Integer>read(body, "$.skippedMedia.length()")).isEqualTo(2);

        for (Integer id : created) {
            String room = mockMvc.perform(get("/api/rooms/" + id).header("Authorization", bearer("ADMIN")))
                    .andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<Boolean>read(room, "$.aiImported")).isFalse();
            String media = mockMvc.perform(get("/api/rooms/" + id + "/media")
                    .header("Authorization", bearer("ADMIN"))).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<Integer>read(media, "$.length()")).isZero();
        }
        // A foreign job is never marked applied by the other project's save.
        assertThat(getJob(foreignJob, bearer("ADMIN")).getResponse().getContentAsString())
                .doesNotContain("appliedAt");
    }

    @Test
    @DisplayName("a save without the new fields behaves as before (no marker, no skipped refs)")
    void saveWithoutImportFieldsUnchanged() throws Exception {
        long projectId = createProject("plain");
        long typeId = anyRoomTypeId();
        Map<String, Object> room = new java.util.LinkedHashMap<>();
        room.put("projectId", projectId);
        room.put("roomTypeId", typeId);
        room.put("label", "Plain");
        room.put("floorArea", 12.5);
        MvcResult saved = save(projectId, bearer("ADMIN"), List.of(room));
        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        String body = saved.getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(body, "$.created.length()")).isEqualTo(1);
        assertThat(JsonPath.<List<?>>read(body, "$.skippedMedia")).isEmpty();
        assertThat(JsonPath.<List<?>>read(body, "$.skippedImportRefs")).isEmpty();
        int id = JsonPath.read(body, "$.created[0]");
        String dto = mockMvc.perform(get("/api/rooms/" + id).header("Authorization", bearer("ADMIN")))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Boolean>read(dto, "$.aiImported")).isFalse();
    }

    // ------------------------------------------------------------------ failures and notifications

    @Test
    @DisplayName("worker failure -> FAILED with a reason code and exactly one ...failed notification")
    void workerFailureNotifiesOnce() throws Exception {
        long projectId = createProject("fail");
        long jobId = startOk(projectId, prompt("STUB:LLM_DOWN"));
        String failed = awaitStatus(jobId, "FAILED");
        String reason = JsonPath.read(failed, "$.failureReason");
        assertThat(reason).startsWith("error.roomsimport.failed.");

        awaitNotifications("ADMIN", "notification.roomsImport.failed", 1);
        Thread.sleep(500); // a duplicate, if any, would have been written by now
        List<NotificationEntity> list = notifications("ADMIN", "notification.roomsImport.failed");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getBody()).isEqualTo(reason);
        assertThat(list.get(0).getDeepLink()).isEqualTo("/projects/" + projectId + "/rooms");
        assertThat(notifications("ADMIN", "notification.roomsImport.completed")).isEmpty();
    }

    @Test
    @DisplayName("sweep timeout fails a stuck job with the timeout reason and notifies exactly once")
    void sweepTimeoutNotifiesOnce() throws Exception {
        long projectId = createProject("stuck");
        UserEntity admin = user("ADMIN");
        long jobId = tx().execute(s -> {
            RoomsImportJobEntity job = new RoomsImportJobEntity();
            job.setProject(entityManager.getReference(ProjectEntity.class, projectId));
            job.setCreator(entityManager.getReference(UserEntity.class, admin.getId()));
            job.setStatus(RoomsImportStatus.MEASURING);
            job.setInputs(new ArrayList<>());
            job.setStartedAt(LocalDateTime.now().minusHours(2));
            return jobDao.save(job).getId();
        });
        jdbc.update("UPDATE rooms_import_jobs SET updated_date = now() - interval '2 hours' WHERE id = ?", jobId);

        sweepService.sweep();
        String failed = awaitStatus(jobId, "FAILED");
        assertThat(JsonPath.<String>read(failed, "$.failureReason")).isEqualTo("error.roomsimport.failed.timeout");

        // A second sweep and a late worker terminal write are 0-row no-ops: still one notification.
        sweepService.sweep();
        assertThat(terminator.fail(jobId, "error.roomsimport.failed.internal")).isZero();
        awaitNotifications("ADMIN", "notification.roomsImport.failed", 1);
        Thread.sleep(300);
        List<NotificationEntity> list = notifications("ADMIN", "notification.roomsImport.failed");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getBody()).isEqualTo("error.roomsimport.failed.timeout");
    }

    // ------------------------------------------------------------------ cancel

    @Test
    @DisplayName("cancel: 204 for an active job, no notification, no further AI calls; again -> 404")
    void cancelActiveJob() throws Exception {
        long projectId = createProject("cancel");
        long jobId = startOk(projectId, prompt("STUB:THREE_ROOMS STUB:SLOW=2"), png("files", "plan.png"));
        await("first measuring call", () -> aiCalls("measure") >= 1, 30_000);
        assertThat(storage.keysWithPrefix("rooms-import/" + jobId + "/in/")).isNotEmpty();

        MvcResult cancelled = cancel(jobId, bearer("ADMIN"));
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(204);
        long callsAtCancel = aiCalls("measure");

        assertThat(getJob(jobId, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(404);
        assertThat(current(projectId, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(204);
        assertThat(cancel(jobId, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(404);

        // The in-flight call (2 s) finishes; no further measuring call may start.
        Thread.sleep(5_000);
        assertThat(aiCalls("measure")).isEqualTo(callsAtCancel);
        assertThat(aiCalls("detect")).isEqualTo(1);
        assertThat(notifications("ADMIN", null)).isEmpty();
        await("stored inputs removed", () -> storage.keysWithPrefix("rooms-import/" + jobId + "/").isEmpty(),
                10_000);
    }

    @Test
    @DisplayName("cancel of a finished job -> 409 not_cancellable; the result stays available")
    void cancelTerminalJobConflicts() throws Exception {
        long projectId = createProject("cancel409");
        long jobId = startOk(projectId, prompt("STUB:THREE_ROOMS"));
        awaitStatus(jobId, "COMPLETED");

        MvcResult r = cancel(jobId, bearer("ADMIN"));
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(getJob(jobId, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("cancel of an unknown job -> 404")
    void cancelUnknownJob() throws Exception {
        assertThat(cancel(987_654_321L, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ single-job rule

    @Test
    @DisplayName("concurrent starts: one 202, the rest 429 with Retry-After and {jobId, stage, startedAt}")
    void concurrentStartsOneWins() throws Exception {
        long projectId = createProject("race");
        int n = 6;
        List<MvcResult> results = runConcurrently(n, () ->
                start(projectId, bearer("ADMIN"), prompt("STUB:THREE_ROOMS STUB:SLOW=3")));

        List<MvcResult> accepted = results.stream().filter(r -> r.getResponse().getStatus() == 202).toList();
        List<MvcResult> rejected = results.stream().filter(r -> r.getResponse().getStatus() == 429).toList();
        assertThat(accepted).hasSize(1);
        assertThat(rejected).hasSize(n - 1);
        long winner = ((Number) JsonPath.read(accepted.get(0).getResponse().getContentAsString(), "$.jobId"))
                .longValue();
        for (MvcResult r : rejected) {
            assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("30");
            String body = r.getResponse().getContentAsString();
            assertThat(((Number) JsonPath.read(body, "$.params.jobId")).longValue()).isEqualTo(winner);
            assertThat(JsonPath.<String>read(body, "$.params.stage")).isNotBlank();
            assertThat(JsonPath.<String>read(body, "$.params.startedAt")).isNotBlank();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rooms_import_jobs WHERE project_id = ?",
                Integer.class, projectId)).isEqualTo(1);

        // An active job also answers 429 to a later sequential start.
        assertThat(start(projectId, bearer("ADMIN"), prompt("STUB:THREE_ROOMS")).getResponse().getStatus())
                .isEqualTo(429);
        assertThat(cancel(winner, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    @DisplayName("a terminal job is replaced by a new start (also under concurrency: one 202, rest 429)")
    void terminalJobIsReplaced() throws Exception {
        long projectId = createProject("replace");
        long first = startOk(projectId, prompt("STUB:THREE_ROOMS"));
        awaitStatus(first, "COMPLETED");

        long second = startOk(projectId, prompt("STUB:THREE_ROOMS STUB:SLOW=2"));
        assertThat(second).isNotEqualTo(first);
        assertThat(getJob(first, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(404);
        assertThat(cancel(second, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(204);

        long third = startOk(projectId, prompt("STUB:THREE_ROOMS"));
        awaitStatus(third, "COMPLETED");
        int n = 5;
        List<MvcResult> results = runConcurrently(n, () ->
                start(projectId, bearer("ADMIN"), prompt("STUB:THREE_ROOMS STUB:SLOW=3")));
        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 202).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 429).count()).isEqualTo(n - 1);
        assertThat(results).allMatch(r -> r.getResponse().getStatus() == 202 || r.getResponse().getStatus() == 429);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rooms_import_jobs WHERE project_id = ?",
                Integer.class, projectId)).isEqualTo(1);
        long current = jdbc.queryForObject("SELECT id FROM rooms_import_jobs WHERE project_id = ?",
                Long.class, projectId);
        assertThat(cancel(current, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    @DisplayName("current endpoint: 204 without a job, 200 while active, 200 completed, 204 after discard")
    void currentEndpointStates() throws Exception {
        long projectId = createProject("current");
        assertThat(current(projectId, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(204);

        long jobId = startOk(projectId, prompt("STUB:THREE_ROOMS STUB:SLOW=2"));
        MvcResult active = current(projectId, bearer("ADMIN"));
        assertThat(active.getResponse().getStatus()).isEqualTo(200);
        String activeBody = active.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(activeBody, "$.jobId")).longValue()).isEqualTo(jobId);
        assertThat(JsonPath.<String>read(activeBody, "$.status")).isNotIn("COMPLETED", "FAILED");
        assertThat(JsonPath.<Object>read(activeBody, "$.stage")).isNotNull();

        awaitStatus(jobId, "COMPLETED");
        MvcResult done = current(projectId, bearer("ADMIN"));
        assertThat(done.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<String>read(done.getResponse().getContentAsString(), "$.status"))
                .isEqualTo("COMPLETED");

        assertThat(discard(jobId, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(204);
        assertThat(current(projectId, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(204);
        // discard is idempotent
        assertThat(discard(jobId, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(204);
        assertThat(mockMvc.perform(delete("/api/rooms/autoimport/" + jobId)
                .header("Authorization", bearer("ADMIN"))).andReturn().getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    @DisplayName("start without prompt and files -> 400 and no job row is created")
    void emptyInputCreatesNoJob() throws Exception {
        long projectId = createProject("empty");
        MvcResult r = start(projectId, bearer("ADMIN"), null);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(current(projectId, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    @DisplayName("object storage not configured -> 503 and no job row (R1.6)")
    void storageUnavailable() throws Exception {
        long projectId = createProject("nostorage");
        storage.setConfigured(false);
        try {
            MvcResult r = start(projectId, bearer("ADMIN"), prompt("STUB:THREE_ROOMS"));
            assertThat(r.getResponse().getStatus()).isEqualTo(503);
        } finally {
            storage.setConfigured(true);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rooms_import_jobs WHERE project_id = ?",
                Integer.class, projectId)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private <T> List<T> runConcurrently(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
