package com.foremen.collab;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.collab.CollabMessages.CellRef;
import com.foremen.collab.CollabMessages.CollabEvent;
import com.foremen.collab.CollabMessages.CollabSnapshot;
import com.foremen.collab.CollabMessages.DraftEntry;
import com.foremen.collab.CollabMessages.PresenceEntry;
import com.foremen.controller.model.ApplyAssignmentsRequest.StagedEditPayload;
import com.foremen.dao.UserDao;

/**
 * FOR-05-13 — the server-authoritative collaboration state of an estimate, held in Redis so every
 * backend instance (and every browser) sees the same thing.
 *
 * <h2>Redis layout (per project estimate)</h2>
 * <ul>
 *   <li>{@code collab:est:{id}:seq} — monotonic counter assigning each staged edit its {@code seq};</li>
 *   <li>{@code collab:est:{id}:order} — ZSET {@code stagedId → seq} (the replay order);</li>
 *   <li>{@code collab:est:{id}:edits} — HASH {@code stagedId → entry body JSON};</li>
 *   <li>{@code collab:est:{id}:presence} — HASH {@code sessionId → presence JSON}.</li>
 * </ul>
 * Draft keys carry a sliding TTL ({@code foremen.collab.draft-ttl-days}) so an abandoned draft
 * expires; presence entries expire by heartbeat age ({@code presence-ttl-seconds}).
 *
 * <h2>Fan-out</h2>
 * Every state change is published as a {@link CollabEvent} on the Redis channel {@link #CHANNEL};
 * {@link CollabEventRelay} (on every instance) forwards it to that instance's STOMP subscribers on
 * {@code /topic/estimate/{id}}. If Redis is unreachable the event is delivered to the local broker
 * directly, and draft/presence calls degrade to no-ops (single-user mode) instead of failing the
 * request — collaboration is an enhancement, never a dependency of saving an estimate.
 */
@Service
public class EstimateCollabService {

    private static final Logger log = LoggerFactory.getLogger(EstimateCollabService.class);

    /** The Redis pub/sub channel carrying every collaboration event (all projects). */
    public static final String CHANNEL = "foremen:collab:estimate";

    /** Number of distinct user colors the frontend palette offers. */
    static final int PALETTE_SIZE = 8;

    private static final String TOPIC_PREFIX = "/topic/estimate/";

    /**
     * Atomic, idempotent add: a known {@code stagedId} is not re-sequenced (returns -1); a full draft
     * is rejected (returns -2); otherwise INCR the counter, store the body, index it by seq, refresh
     * TTLs, and return the new seq. Atomic across instances, so two concurrent adds always get
     * distinct, ordered sequence numbers.
     */
    private static final DefaultRedisScript<Long> ADD_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('HEXISTS', KEYS[3], ARGV[1]) == 1 then return -1 end "
                    + "if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[3]) then return -2 end "
                    + "local seq = redis.call('INCR', KEYS[1]) "
                    + "redis.call('HSET', KEYS[3], ARGV[1], ARGV[2]) "
                    + "redis.call('ZADD', KEYS[2], seq, ARGV[1]) "
                    + "local ttl = tonumber(ARGV[4]) "
                    + "redis.call('EXPIRE', KEYS[1], ttl) "
                    + "redis.call('EXPIRE', KEYS[2], ttl) "
                    + "redis.call('EXPIRE', KEYS[3], ttl) "
                    + "return seq",
            Long.class);

    /** Atomic remove of one entry; returns 1 when it existed, 0 otherwise. */
    private static final DefaultRedisScript<Long> REMOVE_SCRIPT = new DefaultRedisScript<>(
            "local n = redis.call('HDEL', KEYS[2], ARGV[1]) "
                    + "redis.call('ZREM', KEYS[1], ARGV[1]) "
                    + "return n",
            Long.class);

    private final StringRedisTemplate redis;
    /**
     * Resolved lazily: the STOMP infrastructure that creates {@link SimpMessagingTemplate} itself
     * depends (via the channel interceptor) on this service, so an eager reference would be a cycle.
     */
    private final ObjectProvider<SimpMessagingTemplate> messagingTemplate;
    private final UserDao userDao;
    private final CollabProperties properties;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public EstimateCollabService(StringRedisTemplate redis,
                                 ObjectProvider<SimpMessagingTemplate> messagingTemplate,
                                 UserDao userDao,
                                 CollabProperties properties) {
        this.redis = redis;
        this.messagingTemplate = messagingTemplate;
        this.userDao = userDao;
        this.properties = properties;
    }

    /** The stored HASH value of a draft entry (its {@code seq} lives in the ZSET score). */
    public record EntryBody(String stagedId, Long authorUserId, String authorName, int colorIndex,
                     StagedEditPayload payload) {}

    // --- Draft ---------------------------------------------------------------------------------

    /**
     * Adds one staged edit to the shared draft and broadcasts {@code EDIT_ADDED}. Idempotent per
     * {@code stagedId}: re-sending a known id (e.g. after a reconnect) changes nothing.
     *
     * @return the stored entry, or {@code null} when nothing was added (duplicate, full, disabled,
     *         Redis unavailable)
     */
    public DraftEntry addEdit(Long projectId, CollabPrincipal author, String stagedId, StagedEditPayload payload) {
        if (!properties.enabled() || projectId == null || author == null
                || stagedId == null || stagedId.isBlank() || stagedId.length() > 100
                || payload == null || payload.kind() == null) {
            return null;
        }
        EntryBody body = new EntryBody(stagedId, author.userId(), author.name(), colorIndexOf(author.userId()), payload);
        try {
            Long seq = redis.execute(ADD_SCRIPT,
                    List.of(seqKey(projectId), orderKey(projectId), editsKey(projectId)),
                    stagedId, toJson(body), String.valueOf(properties.maxDraftEdits()),
                    String.valueOf(draftTtlSeconds()));
            if (seq == null || seq < 0) {
                if (seq != null && seq == -2) {
                    log.warn("Collab draft for project {} is full ({} edits); edit {} not shared",
                            projectId, properties.maxDraftEdits(), stagedId);
                }
                return null;
            }
            DraftEntry entry = new DraftEntry(seq, stagedId, body.authorUserId(), body.authorName(),
                    body.colorIndex(), payload);
            publish(CollabEvent.editAdded(projectId, entry));
            return entry;
        } catch (RuntimeException e) {
            log.warn("Collab addEdit failed for project {} (Redis unavailable?): {}", projectId, e.getMessage());
            return null;
        }
    }

    /** Removes one staged edit from the shared draft and broadcasts {@code EDIT_REMOVED}. */
    public boolean removeEdit(Long projectId, CollabPrincipal actor, String stagedId) {
        if (!properties.enabled() || projectId == null || stagedId == null || stagedId.isBlank()) {
            return false;
        }
        try {
            Long removed = redis.execute(REMOVE_SCRIPT,
                    List.of(orderKey(projectId), editsKey(projectId)), stagedId);
            if (removed == null || removed == 0) {
                return false;
            }
            publish(CollabEvent.editRemoved(projectId, stagedId,
                    actor == null ? null : actor.userId(), actor == null ? null : actor.name()));
            return true;
        } catch (RuntimeException e) {
            log.warn("Collab removeEdit failed for project {}: {}", projectId, e.getMessage());
            return false;
        }
    }

    /** The shared draft in replay ({@code seq}) order. Empty when disabled / unavailable. */
    public List<DraftEntry> draft(Long projectId) {
        if (!properties.enabled() || projectId == null) {
            return List.of();
        }
        try {
            Set<TypedTuple<String>> ordered = redis.opsForZSet().rangeWithScores(orderKey(projectId), 0, -1);
            if (ordered == null || ordered.isEmpty()) {
                return List.of();
            }
            List<String> ids = new ArrayList<>(ordered.size());
            List<Long> seqs = new ArrayList<>(ordered.size());
            for (TypedTuple<String> t : ordered) {
                if (t.getValue() != null && t.getScore() != null) {
                    ids.add(t.getValue());
                    seqs.add(t.getScore().longValue());
                }
            }
            List<Object> bodies = redis.opsForHash().multiGet(editsKey(projectId), new ArrayList<>(ids));
            List<DraftEntry> out = new ArrayList<>(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                Object raw = bodies == null || i >= bodies.size() ? null : bodies.get(i);
                if (raw == null) {
                    continue; // index/body briefly out of sync — skip the orphan
                }
                EntryBody body = fromJson(raw.toString(), EntryBody.class);
                if (body != null) {
                    out.add(new DraftEntry(seqs.get(i), body.stagedId(), body.authorUserId(),
                            body.authorName(), body.colorIndex(), body.payload()));
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("Collab draft read failed for project {}: {}", projectId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Called after a successful estimate Save: removes the committed entries from the shared draft
     * (all of them when {@code stagedIds} is {@code null}) and broadcasts {@code SAVED} so every other
     * editor refetches the persisted matrix at once.
     */
    public void onSaved(Long projectId, Collection<String> stagedIds, Long actorUserId) {
        if (projectId == null) {
            return;
        }
        String actorName = resolveName(actorUserId);
        List<String> committed = stagedIds == null ? null : List.copyOf(stagedIds);
        if (properties.enabled()) {
            try {
                if (committed == null) {
                    redis.delete(List.of(orderKey(projectId), editsKey(projectId)));
                } else if (!committed.isEmpty()) {
                    redis.opsForZSet().remove(orderKey(projectId), committed.toArray());
                    redis.opsForHash().delete(editsKey(projectId), committed.toArray());
                }
            } catch (RuntimeException e) {
                log.warn("Collab draft prune after save failed for project {}: {}", projectId, e.getMessage());
            }
        }
        // Broadcast even in degraded mode so other open tabs on this instance still refresh.
        publish(CollabEvent.saved(projectId, committed, actorUserId, actorName));
    }

    // --- Presence ------------------------------------------------------------------------------

    /**
     * Records (or refreshes) a session's presence and focused cell, then broadcasts the roster.
     * Also serves as the heartbeat: clients resend their current focus periodically.
     */
    public void touchPresence(Long projectId, CollabPrincipal user, String sessionId, CellRef focus) {
        if (!properties.enabled() || projectId == null || user == null || sessionId == null) {
            return;
        }
        CellRef cleanFocus = focus == null || focus.workItemId() == null || focus.roomId() == null ? null : focus;
        PresenceEntry entry = new PresenceEntry(sessionId, user.userId(), user.name(),
                colorIndexOf(user.userId()), cleanFocus, System.currentTimeMillis());
        try {
            redis.opsForHash().put(presenceKey(projectId), sessionId, toJson(entry));
            redis.expire(presenceKey(projectId), (long) properties.presenceTtlSeconds() * 4, TimeUnit.SECONDS);
            publish(CollabEvent.presence(projectId, presence(projectId)));
        } catch (RuntimeException e) {
            log.warn("Collab presence update failed for project {}: {}", projectId, e.getMessage());
        }
    }

    /** Drops a session from the roster (disconnect / leaving the estimate) and broadcasts it. */
    public void leave(Long projectId, String sessionId) {
        if (!properties.enabled() || projectId == null || sessionId == null) {
            return;
        }
        try {
            redis.opsForHash().delete(presenceKey(projectId), sessionId);
            publish(CollabEvent.presence(projectId, presence(projectId)));
        } catch (RuntimeException e) {
            log.warn("Collab presence leave failed for project {}: {}", projectId, e.getMessage());
        }
    }

    /** The live roster (stale entries — no heartbeat within the TTL — are pruned and omitted). */
    public List<PresenceEntry> presence(Long projectId) {
        if (!properties.enabled() || projectId == null) {
            return List.of();
        }
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(presenceKey(projectId));
            long cutoff = System.currentTimeMillis() - properties.presenceTtlSeconds() * 1000L;
            List<PresenceEntry> live = new ArrayList<>();
            List<Object> stale = new ArrayList<>();
            for (Map.Entry<Object, Object> e : raw.entrySet()) {
                PresenceEntry p = e.getValue() == null ? null : fromJson(e.getValue().toString(), PresenceEntry.class);
                if (p == null || p.lastSeenEpochMs() < cutoff) {
                    stale.add(e.getKey());
                } else {
                    live.add(p);
                }
            }
            if (!stale.isEmpty()) {
                redis.opsForHash().delete(presenceKey(projectId), stale.toArray());
            }
            live.sort(Comparator.comparing(PresenceEntry::userId).thenComparing(PresenceEntry::sessionId));
            return live;
        } catch (RuntimeException e) {
            log.warn("Collab presence read failed for project {}: {}", projectId, e.getMessage());
            return List.of();
        }
    }

    /** The catch-up snapshot (shared draft + roster) a newly-joining client seeds from. */
    public CollabSnapshot snapshot(Long projectId) {
        return new CollabSnapshot(properties.enabled(), draft(projectId), presence(projectId));
    }

    // --- Fan-out -------------------------------------------------------------------------------

    /**
     * Publishes an event to every instance via Redis pub/sub; if Redis is unreachable, delivers it to
     * this instance's subscribers directly so same-instance editors still stay in sync.
     */
    void publish(CollabEvent event) {
        String json = toJson(event);
        if (json == null) {
            return;
        }
        if (properties.enabled()) {
            try {
                redis.convertAndSend(CHANNEL, json);
                return;
            } catch (RuntimeException e) {
                log.warn("Collab publish via Redis failed, delivering locally: {}", e.getMessage());
            }
        }
        deliverLocally(event.projectId(), json);
    }

    /** Sends an already-serialized event to this instance's subscribers of the project topic. */
    void deliverLocally(Long projectId, String json) {
        if (projectId == null || json == null) {
            return;
        }
        SimpMessagingTemplate template = messagingTemplate.getIfAvailable();
        if (template != null) {
            template.convertAndSend(TOPIC_PREFIX + projectId, json);
        }
    }

    /** Extracts the project id from a serialized event (used by the Redis relay). */
    Long projectIdOf(String json) {
        CollabEvent event = fromJson(json, CollabEvent.class);
        return event == null ? null : event.projectId();
    }

    // --- Helpers -------------------------------------------------------------------------------

    /** Resolves a user's display name, or {@code null} when unknown. */
    public String resolveName(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            return userDao.findById(userId).map(u -> u.getName()).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A stable palette index per user, shared by the roster chip and the user's cell cursors. */
    static int colorIndexOf(Long userId) {
        return userId == null ? 0 : (int) Math.floorMod(userId, (long) PALETTE_SIZE);
    }

    private long draftTtlSeconds() {
        return properties.draftTtlDays() * 24L * 3600L;
    }

    private static String base(Long projectId) {
        return "collab:est:" + projectId;
    }

    static String seqKey(Long projectId) {
        return base(projectId) + ":seq";
    }

    static String orderKey(Long projectId) {
        return base(projectId) + ":order";
    }

    static String editsKey(Long projectId) {
        return base(projectId) + ":edits";
    }

    static String presenceKey(Long projectId) {
        return base(projectId) + ":presence";
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Collab serialization failed: {}", e.getMessage());
            return null;
        }
    }

    <T> T fromJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("Collab deserialization failed: {}", e.getMessage());
            return null;
        }
    }
}
