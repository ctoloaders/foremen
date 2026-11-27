package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.estimateai.EstimateAiRoomsSaver;
import com.foremen.service.estimateai.WarningCodes;
import com.foremen.service.roomsimport.RoomsImportFailureException;
import com.foremen.service.roomsimport.RoomsImportJobMapper;
import com.foremen.service.roomsimport.model.ImportResult;
import com.jayway.jsonpath.JsonPath;

/**
 * FOR-05-04 task 3.7 - the {@code SAVING_ROOMS} stage against the real schema: the draft rooms of a stub
 * import are saved through {@code RoomsSaveService} under the job creator's identity while the job is still
 * active (so the Rooms tab can render them), draft media is attached, an unsaveable draft is skipped and
 * reported, and a job that saves nothing in an empty project fails with {@code no_rooms}.
 *
 * <p>Repeatability: generator strategy - every test creates its own project and job under a per-instance
 * run id; nothing is shared between tests.
 *
 * <p>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 4.4
 */
class EstimateAiRoomsSaverIT extends RoomsAutoimportItBase {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Autowired private EstimateAiRoomsSaver saver;
    @Autowired private RoomsImportJobDao jobDao;
    @Autowired private RoomsImportJobMapper jobMapper;

    /** A completed stub import (two rooms with crops), turned into an active {@code SAVING_ROOMS} job. */
    private long estimateJobAtSavingRooms(long projectId) throws Exception {
        long jobId = startOk(projectId, prompt("STUB:IMAGE_CROPS"), png("files", "plan.png"));
        awaitStatus(jobId, "COMPLETED");
        jdbc.update("UPDATE rooms_import_jobs SET mode = 'ESTIMATE', status = 'SAVING_ROOMS', "
                + "completed_at = NULL WHERE id = ?", jobId);
        return jobId;
    }

    /** The job's stored draft result, with every room's {@code room} map passed through {@code editor}. */
    @SuppressWarnings("unchecked")
    private ImportResult draft(long jobId, long typeId, Consumer<List<Map<String, Object>>> editor) {
        Map<String, Object> raw = tx().execute(s -> MAPPER.convertValue(
                jobDao.findById(jobId).orElseThrow().getResult(), Map.class));
        List<Map<String, Object>> rooms = (List<Map<String, Object>>) raw.get("rooms");
        List<Map<String, Object>> roomMaps = rooms.stream().map(r -> (Map<String, Object>) r.get("room")).toList();
        roomMaps.forEach(m -> m.put("roomTypeId", typeId));
        editor.accept(roomMaps);
        RoomsImportJobEntity transientJob = new RoomsImportJobEntity();
        transientJob.setResult(raw);
        return jobMapper.readResult(transientJob);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    @DisplayName("rooms are saved and visible while the job is still active; append-only; media attached")
    void savesRoomsWhileJobActive() throws Exception {
        long projectId = createProject("saver-ok");
        long existing = seedRoom(projectId, "Existing", "10.00");
        long jobId = estimateJobAtSavingRooms(projectId);
        ImportResult draft = draft(jobId, anyRoomTypeId(), rooms -> { });

        EstimateAiRoomsSaver.Outcome outcome = saver.save(jobId, draft);

        assertThat(outcome.savedRoomIds()).hasSize(2);
        assertThat(outcome.skipped()).isEmpty();
        assertThat(outcome.preExistingRooms()).isEqualTo(1);
        assertThat(outcome.savedByTempId()).hasSize(2);

        // the rooms are committed and visible while the job is still active (the project stays locked)
        assertThat(roomDao.findByProjectId(projectId)).hasSize(3);
        assertThat(roomDao.findById(existing)).isPresent();
        assertThat(count("SELECT COUNT(*) FROM rooms WHERE project_id = ? AND ai_imported", projectId))
                .isEqualTo(2);
        String body = getJob(jobId, bearer("ADMIN")).getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("SAVING_ROOMS");
        assertThat(JsonPath.<Object>read(body, "$.roomsSavedAt")).isNotNull();
        assertThat(JsonPath.<Integer>read(body, "$.roomsCreated")).isEqualTo(2);

        // draft media (the cropped room pictures) is attached to the new rooms only
        assertThat(count("SELECT COUNT(*) FROM room_media WHERE room_id = ?", existing)).isZero();
        for (Long roomId : outcome.savedRoomIds()) {
            assertThat(count("SELECT COUNT(*) FROM room_media WHERE room_id = ?", roomId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a draft without a room type is skipped and reported as ROOM_NOT_SAVED")
    void skipsUnsaveableDraft() throws Exception {
        long projectId = createProject("saver-skip");
        long jobId = estimateJobAtSavingRooms(projectId);
        ImportResult draft = draft(jobId, anyRoomTypeId(), rooms -> rooms.get(0).put("roomTypeId", null));

        EstimateAiRoomsSaver.Outcome outcome = saver.save(jobId, draft);

        assertThat(outcome.savedRoomIds()).hasSize(1);
        assertThat(outcome.skipped()).hasSize(1);
        assertThat(outcome.skipped().get(0).reason()).isEqualTo("ROOM_TYPE_UNRESOLVED");
        assertThat(outcome.questions()).singleElement().satisfies(q -> {
            assertThat(q.code()).isEqualTo(WarningCodes.ROOM_NOT_SAVED);
            assertThat(q.params()).containsKeys("label", "reason");
        });
        assertThat(roomDao.findByProjectId(projectId)).hasSize(1);
        assertThat(jobDao.findById(jobId).orElseThrow().getRoomsCreated()).isEqualTo(1);
    }

    @Test
    @DisplayName("nothing saved and no existing rooms: no_rooms failure, rooms_saved_at stays empty")
    void failsWithNoRooms() throws Exception {
        long projectId = createProject("saver-none");
        long jobId = estimateJobAtSavingRooms(projectId);
        ImportResult draft = draft(jobId, anyRoomTypeId(), rooms -> rooms.forEach(m -> m.put("roomTypeId", null)));

        assertThatThrownBy(() -> saver.save(jobId, draft))
                .isInstanceOf(RoomsImportFailureException.class)
                .extracting(e -> ((RoomsImportFailureException) e).getReason())
                .isEqualTo("error.estimateai.failed.no_rooms");

        assertThat(roomDao.findByProjectId(projectId)).isEmpty();
        assertThat(jobDao.findById(jobId).orElseThrow().getRoomsSavedAt()).isNull();
    }

    @Test
    @DisplayName("the creator's ROOMS CREATE permission is re-checked: a role without it saves nothing")
    void recheckCreatorPermission() throws Exception {
        long projectId = createProject("saver-perm");
        long jobId = estimateJobAtSavingRooms(projectId);
        jdbc.update("UPDATE rooms_import_jobs SET created_by_id = ? WHERE id = ?", user("FOREMAN").getId(), jobId);
        ImportResult draft = draft(jobId, anyRoomTypeId(), rooms -> { });

        assertThatThrownBy(() -> saver.save(jobId, draft))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(403));

        assertThat(roomDao.findByProjectId(projectId)).isEmpty();
        assertThat(jobDao.findById(jobId).orElseThrow().getRoomsSavedAt()).isNull();
    }
}
