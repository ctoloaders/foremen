package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.dao.model.RoomsImportStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.roomsimport.RoomsImportJobTerminator;
import com.foremen.service.roomsimport.model.ImportResult;
import com.foremen.service.roomsimport.model.ModelInfo;

/**
 * FOR-05-02 task 4.5 - the DB-level behaviour of {@link RoomsImportJobDao} that unit tests cannot see:
 * the status-conditional JPQL updates / deletes (0 rows for a terminal or missing job), the {@code
 * FOR UPDATE} lock that serialises starts, the stuck / expired finders, and the worker-vs-sweep race on
 * the terminal transition (exactly one winner, exactly one notification).
 *
 * <p>Repeatability: generator strategy - a fresh project (one job row per project) per test.
 *
 * <p>Validates: Requirements 19.1, 19.2, 19.3, 2.5, 17.5, 17.8
 */
class RoomsImportJobDaoIT extends RoomsAutoimportItBase {

    @Autowired private RoomsImportJobDao jobDao;
    @Autowired private RoomsImportJobTerminator terminator;

    private long newJob(long projectId, RoomsImportStatus status) {
        UserEntity admin = user("ADMIN");
        return tx().execute(s -> {
            RoomsImportJobEntity job = new RoomsImportJobEntity();
            job.setProject(entityManager.getReference(ProjectEntity.class, projectId));
            job.setCreator(entityManager.getReference(UserEntity.class, admin.getId()));
            job.setStatus(status);
            job.setInputs(new ArrayList<>());
            job.setStartedAt(LocalDateTime.now());
            return jobDao.save(job).getId();
        });
    }

    private int rows(java.util.function.IntSupplier statement) {
        return tx().execute(s -> statement.getAsInt());
    }

    private String statusOf(long jobId) {
        return jdbc.queryForObject("SELECT status FROM rooms_import_jobs WHERE id = ?", String.class, jobId);
    }

    private static ImportResult emptyResult() {
        return new ImportResult(List.of(), List.of(), List.of(), new ModelInfo("stub"));
    }

    @Test
    @DisplayName("status-conditional updates apply to active jobs and are 0-row no-ops for terminal or missing jobs")
    void conditionalUpdates() {
        long projectId = createProject("dao-cond");
        long jobId = newJob(projectId, RoomsImportStatus.QUEUED);
        LocalDateTime now = LocalDateTime.now();

        assertThat(rows(() -> jobDao.updateStatusIfActive(jobId, RoomsImportStatus.EXTRACTING, now,
                RoomsImportJobDao.TERMINAL))).isEqualTo(1);
        assertThat(statusOf(jobId)).isEqualTo("EXTRACTING");
        assertThat(rows(() -> jobDao.touchIfActive(jobId, now, RoomsImportJobDao.TERMINAL))).isEqualTo(1);

        assertThat(rows(() -> jobDao.terminateIfActive(jobId, RoomsImportStatus.COMPLETED, null, now,
                now.plusDays(1), 3, 0, "stub", RoomsImportJobDao.TERMINAL))).isEqualTo(1);
        assertThat(statusOf(jobId)).isEqualTo("COMPLETED");

        // Terminal now: every conditional statement is a no-op.
        assertThat(rows(() -> jobDao.updateStatusIfActive(jobId, RoomsImportStatus.MEASURING, now,
                RoomsImportJobDao.TERMINAL))).isZero();
        assertThat(rows(() -> jobDao.touchIfActive(jobId, now, RoomsImportJobDao.TERMINAL))).isZero();
        assertThat(rows(() -> jobDao.terminateIfActive(jobId, RoomsImportStatus.FAILED, "x", now,
                now.plusDays(1), null, null, null, RoomsImportJobDao.TERMINAL))).isZero();
        assertThat(rows(() -> jobDao.deleteIfActive(jobId, RoomsImportJobDao.TERMINAL))).isZero();
        assertThat(statusOf(jobId)).isEqualTo("COMPLETED");

        // Missing job.
        assertThat(rows(() -> jobDao.updateStatusIfActive(-1L, RoomsImportStatus.MEASURING, now,
                RoomsImportJobDao.TERMINAL))).isZero();
        assertThat(rows(() -> jobDao.deleteIfActive(-1L, RoomsImportJobDao.TERMINAL))).isZero();

        // Unconditional discard removes the terminal job.
        assertThat(rows(() -> jobDao.deleteByJobId(jobId))).isEqualTo(1);
        assertThat(jobDao.findByProjectId(projectId)).isEmpty();
    }

    @Test
    @DisplayName("deleteIfActive removes an active job; a stage update after it is a 0-row no-op")
    void deleteIfActiveStopsLaterWrites() {
        long projectId = createProject("dao-del");
        long jobId = newJob(projectId, RoomsImportStatus.MEASURING);

        assertThat(rows(() -> jobDao.deleteIfActive(jobId, RoomsImportJobDao.TERMINAL))).isEqualTo(1);
        assertThat(jobDao.findById(jobId)).isEmpty();
        assertThat(rows(() -> jobDao.updateStatusIfActive(jobId, RoomsImportStatus.VALIDATING,
                LocalDateTime.now(), RoomsImportJobDao.TERMINAL))).isZero();
        assertThat(rows(() -> jobDao.terminateIfActive(jobId, RoomsImportStatus.COMPLETED, null,
                LocalDateTime.now(), LocalDateTime.now().plusDays(1), 1, 0, "m", RoomsImportJobDao.TERMINAL)))
                .isZero();
    }

    @Test
    @DisplayName("stuck and expired finders select only the matching jobs")
    void stuckAndExpiredFinders() {
        long stuck = newJob(createProject("dao-stuck"), RoomsImportStatus.DETECTING_ROOMS);
        long fresh = newJob(createProject("dao-fresh"), RoomsImportStatus.DETECTING_ROOMS);
        long expired = newJob(createProject("dao-exp"), RoomsImportStatus.COMPLETED);
        long retained = newJob(createProject("dao-keep"), RoomsImportStatus.COMPLETED);
        jdbc.update("UPDATE rooms_import_jobs SET updated_date = now() - interval '3 hours' WHERE id IN (?, ?)",
                stuck, expired);
        jdbc.update("UPDATE rooms_import_jobs SET expires_at = now() - interval '1 hour' WHERE id = ?", expired);
        jdbc.update("UPDATE rooms_import_jobs SET expires_at = now() + interval '1 day' WHERE id = ?", retained);

        List<Long> stuckIds = jobDao.findStuckIds(LocalDateTime.now().minusMinutes(15), RoomsImportJobDao.TERMINAL);
        assertThat(stuckIds).contains(stuck).doesNotContain(fresh, expired, retained);
        List<Long> expiredIds = jobDao.findExpiredIds(LocalDateTime.now(), RoomsImportJobDao.TERMINAL);
        assertThat(expiredIds).contains(expired).doesNotContain(retained, stuck, fresh);
    }

    @Test
    @DisplayName("worker vs sweep race on the terminal write: exactly one winner, exactly one notification")
    void terminalRaceHasOneWinner() throws Exception {
        for (int round = 0; round < 3; round++) {
            long projectId = createProject("dao-race" + round);
            long jobId = newJob(projectId, RoomsImportStatus.MEASURING);
            int before = notifications("ADMIN", null).size();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                Future<Integer> worker = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return terminator.complete(jobId, emptyResult());
                });
                Future<Integer> sweep = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return terminator.fail(jobId, "error.roomsimport.failed.timeout");
                });
                ready.await();
                go.countDown();
                int total = worker.get(30, TimeUnit.SECONDS) + sweep.get(30, TimeUnit.SECONDS);
                assertThat(total).as("exactly one terminal write wins").isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }

            await("one notification", () -> notifications("ADMIN", null).size() >= before + 1, 10_000);
            Thread.sleep(300);
            assertThat(notifications("ADMIN", null)).hasSize(before + 1);
            assertThat(statusOf(jobId)).isIn("COMPLETED", "FAILED");
        }
    }

    @Test
    @DisplayName("FOR UPDATE on the project's job row blocks a second locker until the first transaction ends")
    void forUpdateSerialisesLockers() throws Exception {
        long projectId = createProject("dao-lock");
        newJob(projectId, RoomsImportStatus.COMPLETED);

        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicLong holdStart = new AtomicLong();
        AtomicLong holdEnd = new AtomicLong();
        try {
            Future<?> holder = pool.submit(() -> tx().executeWithoutResult(s -> {
                jobDao.findByProjectIdForUpdate(projectId).orElseThrow();
                holdStart.set(System.nanoTime());
                locked.countDown();
                try {
                    Thread.sleep(1_500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                holdEnd.set(System.nanoTime());
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            long acquiredAt = tx().execute(s -> {
                jobDao.findByProjectIdForUpdate(projectId).orElseThrow();
                return System.nanoTime();
            });
            holder.get(10, TimeUnit.SECONDS);
            assertThat(acquiredAt).as("second locker acquired the row only after the first one finished")
                    .isGreaterThanOrEqualTo(holdEnd.get());
            assertThat(TimeUnit.NANOSECONDS.toMillis(acquiredAt - holdStart.get())).isGreaterThanOrEqualTo(1_000);
        } finally {
            pool.shutdownNow();
        }
    }
}
