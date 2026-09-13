package com.foremen.qa.support;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Run-unique test-data generator (Requirement 3.1).
 *
 * <p>Every JVM run computes a single {@code run-id} (a compact timestamp token) once, and all data
 * keys handed out during the run are derived from it plus a monotonically increasing sequence. This
 * guarantees two properties the suite depends on:
 * <ul>
 *   <li><b>Collision-free within a run</b> — the sequence disambiguates repeated calls, so a
 *       scenario that creates two users/roles/projects never clashes with itself.</li>
 *   <li><b>Collision-free across runs</b> — the {@code run-id} differs per run, so re-running the
 *       suite against the same stack never hits leftover data from a previous run
 *       (Requirement 3.5).</li>
 * </ul>
 *
 * <p>Generated shapes mirror the design's data-key table:
 * <ul>
 *   <li>emails: {@code test+{run-id}+{seq}@example.com}</li>
 *   <li>dictionary codes: {@code qa{run-id}{seq}}</li>
 *   <li>project names: {@code QA Project {run-id}} (with a {@code #seq} suffix for extras)</li>
 *   <li>role codes: {@code TESTROLE_{run-id}_{seq}}</li>
 *   <li>passwords: run-unique, satisfy common complexity rules (upper/lower/digit/symbol)</li>
 * </ul>
 *
 * <p>All methods are thread-safe. The class is stateless from the caller's perspective: the
 * {@code run-id} is a process-wide constant and the sequence is a shared atomic counter.
 */
public final class DataGen {

    /** One run-id per JVM run: compact, lexically increasing, filesystem/identifier-safe. */
    private static final String RUN_ID = computeRunId();

    /** Shared sequence so every generated key across the run is unique. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    private DataGen() {
    }

    /** The per-run identifier shared by all generated keys (also used in report folder names). */
    public static String runId() {
        return RUN_ID;
    }

    /** Next value of the shared sequence (also useful for ad-hoc unique suffixes). */
    public static int nextSeq() {
        return SEQ.incrementAndGet();
    }

    /** A run-unique login email, e.g. {@code test+7k3f9a+1@example.com}. */
    public static String nextEmail() {
        return "test+" + RUN_ID + "+" + nextSeq() + "@example.com";
    }

    /**
     * A run-unique dictionary code, e.g. {@code qa7k3f9a3}. Codes are alphanumeric and kept short so
     * they fit typical {@code code} column limits.
     */
    public static String nextCode() {
        return "qa" + RUN_ID + nextSeq();
    }

    /** A run-unique project name, e.g. {@code QA Project 7k3f9a #4}. */
    public static String nextProjectName() {
        return "QA Project " + RUN_ID + " #" + nextSeq();
    }

    /** A run-unique custom role code, e.g. {@code TESTROLE_7K3F9A_5}. */
    public static String nextRoleCode() {
        return "TESTROLE_" + RUN_ID.toUpperCase() + "_" + nextSeq();
    }

    /**
     * A run-unique password satisfying the usual complexity rules (upper, lower, digit, symbol),
     * e.g. {@code Qa7k3f9a-6!}. Only generated test data; never a real credential.
     */
    public static String nextPassword() {
        return "Qa" + RUN_ID + "-" + nextSeq() + "!";
    }

    /**
     * Compute a compact run-id from the current time. Base-36 of the epoch millis keeps it short
     * while remaining unique per run and lexically ordered.
     */
    private static String computeRunId() {
        return Long.toString(System.currentTimeMillis(), 36);
    }
}
