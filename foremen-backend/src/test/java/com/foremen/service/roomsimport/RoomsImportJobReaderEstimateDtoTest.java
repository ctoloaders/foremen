package com.foremen.service.roomsimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.foremen.controller.model.RoomsImportJobDto;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.dao.model.RoomsImportStatus;

/** FOR-05-04 task 1.3 (Requirement 2.3): the job DTO exposes the ESTIMATE-mode fields. */
@DisplayName("FOR-05-04: RoomsImportJobDto estimate fields")
class RoomsImportJobReaderEstimateDtoTest {

    private static RoomsImportJobEntity job(String mode, RoomsImportStatus status) {
        RoomsImportJobEntity job = new RoomsImportJobEntity();
        job.setId(7L);
        ProjectEntity project = new ProjectEntity();
        project.setId(3L);
        job.setProject(project);
        job.setMode(mode);
        job.setStatus(status);
        return job;
    }

    @Test
    @DisplayName("completed ESTIMATE job exposes mode, phase, roomsSavedAt, roomsCreated, result.estimate")
    void completedEstimateJob() {
        RoomsImportJobEntity job = job("ESTIMATE", RoomsImportStatus.COMPLETED);
        LocalDateTime saved = LocalDateTime.of(2026, 5, 1, 10, 0);
        job.setRoomsSavedAt(saved);
        job.setRoomsCreated(4);
        job.setResult(Map.of("estimate", Map.of("lines", 12)));

        RoomsImportJobDto dto = RoomsImportJobReader.toDto(job);

        assertThat(dto.mode()).isEqualTo("ESTIMATE");
        assertThat(dto.phase()).isEqualTo("ESTIMATE");
        assertThat(dto.roomsSavedAt()).isEqualTo(saved);
        assertThat(dto.roomsCreated()).isEqualTo(4);
        assertThat(dto.result()).containsKey("estimate");
    }

    @Test
    @DisplayName("phase is ROOMS for a ROOMS_ONLY job and for an ESTIMATE job still in the rooms stages")
    void phaseDerivation() {
        assertThat(RoomsImportJobReader.toDto(job("ROOMS_ONLY", RoomsImportStatus.COMPLETED)).phase())
                .isEqualTo("ROOMS");
        assertThat(RoomsImportJobReader.toDto(job("ESTIMATE", RoomsImportStatus.MEASURING)).phase())
                .isEqualTo("ROOMS");
        assertThat(RoomsImportJobReader.toDto(job("ESTIMATE", RoomsImportStatus.PLANNING_WORKS)).phase())
                .isEqualTo("ESTIMATE");
        RoomsImportJobDto rooms = RoomsImportJobReader.toDto(job("ROOMS_ONLY", RoomsImportStatus.QUEUED));
        assertThat(rooms.mode()).isEqualTo("ROOMS_ONLY");
    }

    /** FOR-05-04 task 9.5 (Requirement 26.2): action log and counters; unknown counters are null. */
    @Test
    void toDto_exposesActionLogAndCounters_unknownAreNull() {
        RoomsImportJobEntity job = job("ESTIMATE", RoomsImportStatus.PLANNING_WORKS);
        job.setActionLog(java.util.List.of(Map.of("code", "WORK_ASSIGNED"), Map.of("code", "ROOM_SAVED")));
        job.setRoomsTotal(6);
        job.setRoomsDone(4);

        RoomsImportJobDto dto = RoomsImportJobReader.toDto(job);

        assertThat(dto.actionLog()).extracting(e -> e.get("code")).containsExactly("WORK_ASSIGNED", "ROOM_SAVED");
        assertThat(dto.roomsTotal()).isEqualTo(6);
        assertThat(dto.roomsDone()).isEqualTo(4);
        assertThat(dto.worksTotal()).isNull();
        assertThat(dto.worksDone()).isNull();

        com.fasterxml.jackson.databind.JsonNode json =
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().valueToTree(dto);
        assertThat(json.has("worksTotal")).isTrue();
        assertThat(json.get("worksTotal").isNull()).isTrue();
        assertThat(RoomsImportJobReader.toDto(job("ESTIMATE", RoomsImportStatus.QUEUED)).actionLog()).isEmpty();
    }
}
