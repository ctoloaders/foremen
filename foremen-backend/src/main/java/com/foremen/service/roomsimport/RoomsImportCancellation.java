package com.foremen.service.roomsimport;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.foremen.dao.RoomsImportJobDao;

/**
 * FOR-05-02 (Requirement 24.3; design B2a) — the cooperative cancellation signal: a job is cancelled
 * when its row no longer exists. {@link #assertActive} is a cheap {@code existsById}, cached for a short
 * time (default 1 s) so a burst of per-room checks does not hammer the database.
 */
@Component
public class RoomsImportCancellation {

    static final Duration DEFAULT_CACHE = Duration.ofSeconds(1);

    private final RoomsImportJobDao jobDao;
    private final long cacheNanos;
    private final ConcurrentHashMap<Long, Long> lastSeenActive = new ConcurrentHashMap<>();

    @Autowired
    public RoomsImportCancellation(RoomsImportJobDao jobDao) {
        this(jobDao, DEFAULT_CACHE);
    }

    public RoomsImportCancellation(RoomsImportJobDao jobDao, Duration cache) {
        this.jobDao = jobDao;
        this.cacheNanos = cache == null ? 0 : Math.max(0, cache.toNanos());
    }

    /**
     * @throws JobCancelledException when the job row is gone
     */
    public void assertActive(Long jobId) {
        long now = System.nanoTime();
        Long seen = lastSeenActive.get(jobId);
        if (seen != null && cacheNanos > 0 && now - seen < cacheNanos) {
            return;
        }
        if (!jobDao.existsById(jobId)) {
            lastSeenActive.remove(jobId);
            throw new JobCancelledException(jobId);
        }
        lastSeenActive.put(jobId, now);
        if (lastSeenActive.size() > 256) {
            long expiry = TimeUnit.SECONDS.toNanos(60);
            lastSeenActive.values().removeIf(t -> now - t > expiry);
        }
    }
}
