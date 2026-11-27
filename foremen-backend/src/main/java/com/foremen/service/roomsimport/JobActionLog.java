package com.foremen.service.roomsimport;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.RoomsImportStatus;

/**
 * FOR-05-04 rework (Requirements 26.1, 26.3; design R6) — the bounded, newest-first action log of an
 * AI estimate job ({@code rooms_import_jobs.action_log}).
 *
 * <p>{@link #append} is one SQL {@code UPDATE} that prepends the entry and trims the array to
 * {@value #MAX_ENTRIES} elements. The row lock taken by the update serializes concurrent appends, so none
 * is lost. It joins the caller's transaction when there is one (so the log never shows an action that was
 * not committed) and otherwise runs in its own new transaction ({@code REQUIRED} semantics). Entries hold
 * a code and small parameters only, never document text.
 */
@Component
public class JobActionLog {

    /** Maximum number of retained entries (Requirement 26.1). */
    public static final int MAX_ENTRIES = 200;

    public static final String ROOM_MEASURED = "ROOM_MEASURED";
    public static final String ROOM_SAVED = "ROOM_SAVED";
    public static final String PLAN_READY = "PLAN_READY";
    public static final String WORK_ASSIGNED = "WORK_ASSIGNED";
    public static final String MATERIALS_SEEDED = "MATERIALS_SEEDED";
    public static final String ASSIGNMENT_REJECTED = "ASSIGNMENT_REJECTED";
    public static final String SUMMARY_WRITTEN = "SUMMARY_WRITTEN";

    private static final String SQL = """
            UPDATE rooms_import_jobs
               SET action_log = (
                   SELECT coalesce(jsonb_agg(t.e ORDER BY t.ord), '[]'::jsonb)
                     FROM jsonb_array_elements(
                              jsonb_build_array(CAST(? AS jsonb)) || coalesce(action_log, '[]'::jsonb)
                          ) WITH ORDINALITY AS t(e, ord)
                    WHERE t.ord <= ?)
             WHERE id = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** Spring constructor: the application has no shared Jackson 2 {@code ObjectMapper} bean (Spring Boot 4). */
    @Autowired
    public JobActionLog(JdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper());
    }

    public JobActionLog(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Prepends {@code { at, stage, code, params }} to the job's log.
     *
     * @return affected rows ({@code 0} when the job is gone)
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public int append(Long jobId, RoomsImportStatus stage, String code, Map<String, Object> params) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("at", LocalDateTime.now().toString());
        entry.put("stage", stage == null ? null : stage.name());
        entry.put("code", code);
        entry.put("params", params == null ? Map.of() : params);
        String json;
        try {
            json = objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Action log params are not serializable", e);
        }
        return jdbc.update(SQL, json, MAX_ENTRIES, jobId);
    }
}
