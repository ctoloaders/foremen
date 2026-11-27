package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportInputMode;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkReportStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FOR-06-01 bug-report Round 4: the work-report audit snapshot must be flat (association ids and
 * plain values only). Serializing the whole JPA graph exhausted the heap on a large project.
 */
class WorkReportAuditSnapshotTest {

    @Test
    @DisplayName("audit snapshot holds ids and plain values, never the associated entities")
    @SuppressWarnings("unchecked")
    void snapshotIsFlat() {
        ProjectEntity project = new ProjectEntity();
        project.setId(9617L);
        // A back-reference that the old whole-graph serialization would have followed.
        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setId(1L);
        UserEntity worker = new UserEntity();
        worker.setId(23907L);
        UserEntity author = new UserEntity();
        author.setId(11L);
        RoomEntity room = new RoomEntity();
        room.setId(4933L);

        WorkReportEntity report = new WorkReportEntity();
        report.setId(5L);
        report.setProject(project);
        report.setWorkItem(workItem);
        report.setWorker(worker);
        report.setAuthor(author);
        report.setPerformedFrom(LocalDate.of(2026, 11, 27));
        report.setPerformedTo(LocalDate.of(2026, 11, 27));
        report.setStatus(WorkReportStatus.SUBMITTED);
        report.setTotalQuantity(new BigDecimal("12.5"));

        WorkReportRoomEntity row = new WorkReportRoomEntity();
        row.setReport(report); // cycle: report -> rooms -> report
        row.setRoom(room);
        row.setQuantity(new BigDecimal("12.5"));
        row.setPlanQuantity(new BigDecimal("25"));
        row.setInputMode(WorkReportInputMode.PERCENT);
        report.setRooms(new ArrayList<>(List.of(row)));

        Map<String, Object> snap = WorkReportService.auditSnapshot(report);

        assertThat(snap).containsEntry("id", 5L)
                .containsEntry("projectId", 9617L)
                .containsEntry("workItemId", 1L)
                .containsEntry("workerId", 23907L)
                .containsEntry("authorId", 11L)
                .containsEntry("approvedById", null)
                .containsEntry("status", "SUBMITTED");
        // No value is an entity: only ids, scalars and the plain room list.
        assertThat(snap.values()).noneMatch(v -> v instanceof com.foremen.dao.model.BaseEntity);
        List<Map<String, Object>> rooms = (List<Map<String, Object>>) snap.get("rooms");
        assertThat(rooms).hasSize(1);
        assertThat(rooms.get(0)).containsEntry("roomId", 4933L)
                .containsEntry("inputMode", "PERCENT")
                .doesNotContainKey("report");
    }

    @Test
    @DisplayName("serializeEntity returns null for null and a JSON object for a cyclic report")
    void serializeHandlesNullAndCycles() {
        WorkReportEntity report = new WorkReportEntity();
        report.setId(7L);
        WorkReportRoomEntity row = new WorkReportRoomEntity();
        row.setReport(report);
        report.setRooms(new ArrayList<>(List.of(row)));

        WorkReportService service = org.mockito.Mockito.mock(WorkReportService.class,
                org.mockito.Mockito.CALLS_REAL_METHODS);

        assertThat(service.serializeEntity(null)).isNull();
        String json = service.serializeEntity(report);
        assertThat(json).startsWith("{").contains("\"id\":7").contains("\"rooms\":[");
        assertThat(json).doesNotContain("serialization_failed");
    }
}
