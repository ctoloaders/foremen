package com.foremen.controller.model;

import java.util.List;

/**
 * Request payload for the {@code Worker_Record_Flow} ({@code POST /api/users/worker}, FOR-05-09
 * Requirement 13). Creates one uninvited WORKER user (no password, cannot authenticate) plus one
 * WORKER membership on the target project in a single atomic unit; no invitation email is sent.
 *
 * <p>The role is fixed to {@code WORKER} server-side and is never accepted from the caller — any
 * role / status / active flag in the request is ignored (Requirement 13 criterion 1). The payload
 * carries no bean-validation annotations: the {@code Worker_Record_Flow} validates every field in
 * the canonical record-flow order (Requirement 13 criteria 2–6, 11), collecting <em>all</em>
 * offending fields into one 400 response (Requirement 13 criterion 4), which bean validation's
 * fail-fast per-field annotations cannot express as cleanly.
 *
 * <p>Field shape (validated by the flow, Requirement 13 criterion 2):
 * <ul>
 *   <li>{@code workerKind} — exactly {@code PERSON} or {@code COMPANY} (case-sensitive);</li>
 *   <li>{@code name} — the person name ({@code PERSON}) or company name ({@code COMPANY}),
 *       1–255 characters after trimming;</li>
 *   <li>{@code email} — optional; when supplied, 1–254 characters after trimming and syntactically
 *       valid; a supplied email must be unique case-insensitively (Requirement 13 criterion 7);</li>
 *   <li>{@code phone} — optional; the FOR-05-09 phone rule (digits, spaces, {@code + - ( )},
 *       {@code +} first only, 7–15 digits);</li>
 *   <li>{@code contactPerson} — {@code COMPANY} only; optional; 1–255 characters after trimming;</li>
 *   <li>{@code nip} — {@code COMPANY} only; optional; normalized + checksum-validated (criterion 5);</li>
 *   <li>{@code workerTypeId} — optional; a positive-integer id of an Active_Worker_Type (else the
 *       member becomes an Uncategorized_Worker);</li>
 *   <li>{@code tags} — optional; normalized per Requirement 15 criterion 3.</li>
 * </ul>
 * An optional field that is absent, null, or empty / whitespace-only after trimming is treated as
 * not supplied (Requirement 13 criterion 2).
 */
public record WorkerRecordRequest(
        String workerKind,
        String name,
        String email,
        String phone,
        String contactPerson,
        String nip,
        Long workerTypeId,
        List<String> tags,
        Long projectId
) {}
