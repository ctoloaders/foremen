package com.foremen.service.estimateai.chat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.config.estimateai.EstimateAiProperties;
import com.foremen.dao.OfferAiChatMessageDao;
import com.foremen.dao.OfferDao;
import com.foremen.dao.model.OfferAiChatMessageEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.OfferService;
import com.foremen.service.estimate.CellMetaInput;
import com.foremen.service.estimate.EstimateScratchService;
import com.foremen.service.estimate.EstimateScratchService.Seed;
import com.foremen.service.estimateai.AiProjectLockGuard;
import com.foremen.service.estimateai.ai.EstimateAiException;
import com.foremen.service.estimateai.ai.EstimateAiPrompts;
import com.foremen.service.estimateai.ai.OfferChatClient;
import com.foremen.service.estimateai.ai.OfferChatClient.ChatRequest;
import com.foremen.service.estimateai.ai.OfferChatClient.ChatResponse;
import com.foremen.service.estimateai.ai.OfferChatClient.ChatTurn;
import com.foremen.service.estimateai.ai.OfferChatClient.ToolCall;
import com.foremen.service.estimateai.ai.OfferChatClient.ToolResult;
import com.foremen.service.offer.OfferSnapshot;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import com.foremen.service.roomsimport.EstimateAiErrors;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;

/**
 * FOR-05-04 (design B8; Requirements 10.1-10.8, 11.5-11.9, 9.5) — the offer chat.
 *
 * <p>A turn runs the model with the fixed toolset in a bounded loop. Write tools only fill the per-turn
 * {@link EditBuffer}; at the end the edits are replayed in one scratch run seeded from the offer snapshot,
 * the result replaces the snapshot ({@link OfferService#replaceAiEditedSnapshot}) and the turn rows
 * (user + assistant, with {@code snapshot_before}) are written in the same transaction. Nothing is
 * persisted before that point, so a model failure leaves no trace (Requirement 10.8).</p>
 *
 * <p>Concurrency: a per-offer in-JVM permit plus a transaction-scoped advisory lock and a revision check at
 * apply time (other instances). A wide change (more than the configured number of lines, or a project-wide
 * price adjustment) ends the turn with a {@code pendingAction}; the confirming request re-runs the stored
 * write tool calls on the unchanged snapshot version, so exactly those edits are applied.</p>
 */
@Slf4j
@Service
public class OfferAiChatService {

    public static final String MSG_BUSY = "error.estimateai.chat.busy";
    public static final String MSG_RATE = "error.estimateai.chat.rate";
    public static final String MSG_UNDO_STALE = "error.estimateai.chat.undo_stale";
    public static final String MSG_PENDING_EXPIRED = "error.estimateai.chat.pending_expired";
    public static final String MSG_NOT_EDITABLE = "error.offer.not.editable";
    public static final String MSG_SNAPSHOT_MISSING = "error.offer.snapshot.missing";
    public static final String MSG_NOT_FOUND = "error.entity.not.found";
    public static final String MSG_VALIDATION = "error.validation";
    public static final String MSG_FORBIDDEN = "error.estimateai.chat.forbidden";

    /** Backend-generated assistant texts (codes rendered by the frontend). */
    public static final String TEXT_APPLIED = "estimateai.chat.applied";
    public static final String TEXT_PENDING = "estimateai.chat.pending";
    public static final String TEXT_REJECTED = "estimateai.chat.edit_rejected";
    public static final String TEXT_MAX_ROUNDS = "estimateai.chat.max_rounds";
    public static final String TEXT_UNDONE = "estimateai.chat.undone";

    public static final int MAX_MESSAGE_CHARS = 4000;
    static final String ROLE_USER = "USER";
    static final String ROLE_ASSISTANT = "ASSISTANT";
    static final String ROLE_SYSTEM = "SYSTEM";
    private static final String CLIENT_ROLE = "CLIENT";
    private static final long ADVISORY_NAMESPACE = 0x05A1L << 32;

    /** One message as the API returns it (staff only). */
    public record MessageDto(Long id, String role, String content, List<Map<String, Object>> toolCalls,
                             Map<String, Object> pendingAction, Integer versionBefore, Integer versionAfter,
                             boolean undone, LocalDateTime createdAt) {
    }

    /** The updated offer summary returned with a turn. */
    public record OfferSummary(Long id, String status, Integer revision, BigDecimal totalNet, BigDecimal totalVat,
                               BigDecimal totalGross) {
    }

    /** Result of a turn: {@code messages = [user, assistant]}. */
    public record TurnResult(List<MessageDto> messages, boolean applied, OfferSummary offer,
                             Integer snapshotVersion) {
    }

    /** Result of an undo: the appended system message and the updated offer. */
    public record UndoResult(List<MessageDto> messages, OfferSummary offer, Integer snapshotVersion) {
    }

    private record Caller(Long userId, String role) {
    }

    private record Pre(Long projectId, OfferSnapshot snapshot, int revision) {
    }

    private enum Kind { NONE, APPLY, PENDING, REJECTED }

    private final OfferDao offerDao;
    private final OfferAiChatMessageDao messageDao;
    private final OfferService offerService;
    private final EstimateScratchService scratch;
    private final OfferChatToolRegistry registry;
    private final OfferChatClient client;
    private final ChatCatalog catalog;
    private final ForemenPermissionEvaluator evaluator;
    private final EstimateAiProperties properties;
    private final AiProjectLockGuard lockGuard;
    private final PlatformTransactionManager transactionManager;
    private final EntityManager entityManager;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Map<Long, Semaphore> permits = new ConcurrentHashMap<>();

    public OfferAiChatService(OfferDao offerDao, OfferAiChatMessageDao messageDao, OfferService offerService,
                              EstimateScratchService scratch, OfferChatToolRegistry registry,
                              OfferChatClient client, ChatCatalog catalog, ForemenPermissionEvaluator evaluator,
                              EstimateAiProperties properties, AiProjectLockGuard lockGuard,
                              PlatformTransactionManager transactionManager, EntityManager entityManager) {
        this.offerDao = offerDao;
        this.messageDao = messageDao;
        this.offerService = offerService;
        this.scratch = scratch;
        this.registry = registry;
        this.client = client;
        this.catalog = catalog;
        this.evaluator = evaluator;
        this.properties = properties;
        this.lockGuard = lockGuard;
        this.transactionManager = transactionManager;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------ history

    /** Conversation of the offer, oldest first. Staff only (a CLIENT gets 404). */
    public List<MessageDto> history(Long offerId) {
        Caller caller = caller();
        offerService.assertOfferAccess(offerId);
        denyClient(caller);
        if (!offerDao.existsById(offerId)) {
            throw notFound();
        }
        return tx(true).execute(s -> messageDao.findByOfferIdOrderByIdAsc(offerId).stream()
                .map(this::dto).toList());
    }

    // ------------------------------------------------------------------ turn

    /**
     * Runs one chat turn.
     *
     * @param confirmPendingId id of the assistant message whose {@code pendingAction} the user confirms, or null
     */
    public TurnResult send(Long offerId, String message, String clientMessageId, Long confirmPendingId) {
        Caller caller = caller();
        String text = message == null ? "" : message.strip();
        if (confirmPendingId == null && text.isEmpty() || text.length() > MAX_MESSAGE_CHARS) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, MSG_VALIDATION).withField("message");
        }
        Pre pre = preflight(offerId, caller, true);
        if (!client.isEnabled()) {
            throw unavailable();
        }
        Semaphore permit = permits.computeIfAbsent(offerId, k -> new Semaphore(1));
        if (!permit.tryAcquire()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, MSG_BUSY);
        }
        try {
            assertRate(offerId);
            return confirmPendingId != null
                    ? confirmTurn(offerId, pre, caller, text, confirmPendingId)
                    : modelTurn(offerId, pre, caller, text);
        } finally {
            permit.release();
        }
    }

    private TurnResult modelTurn(Long offerId, Pre pre, Caller caller, String text) {
        EstimateAiProperties.Chat cfg = properties.chat();
        EditBuffer buffer = new EditBuffer();
        ToolContext ctx = new ToolContext(caller.role(), pre.projectId(), offerId, null, pre.snapshot(), buffer);
        List<ChatTurn> history = recentHistory(offerId, cfg.historyTurns());
        String system = EstimateAiPrompts.chatSystem().text();
        String snapshotText = SnapshotTextRenderer.render(pre.snapshot(), snapshotWorkCodes(pre.snapshot()),
                null, SnapshotTextRenderer.DEFAULT_MAX_CHARS);
        List<ToolResult> results = new ArrayList<>();
        List<Map<String, Object>> record = new ArrayList<>();
        List<Map<String, Object>> writeCalls = new ArrayList<>();
        String finalText = null;
        try {
            for (int round = 0; round < cfg.maxToolRounds(); round++) {
                ChatResponse response = client.chat(new ChatRequest(system, snapshotText, history, text, results,
                        registry.declarations()));
                if (response.toolCalls().isEmpty()) {
                    finalText = response.text() == null ? "" : response.text().strip();
                    break;
                }
                for (ToolCall call : response.toolCalls()) {
                    ToolOutput out = registry.run(ctx, call.name(), call.args());
                    results.add(new ToolResult(call.name(), out.toJson(mapper)));
                    record.add(callRecord(call, out));
                    OfferChatTool tool = registry.tool(call.name());
                    if (out.ok() && tool != null && tool.permission().isWrite()) {
                        writeCalls.add(callRecord(call, null));
                    }
                }
            }
        } catch (EstimateAiException e) {
            log.warn("Offer chat model call failed for offer {}: {}", offerId, e.reason());
            throw unavailable();
        }
        boolean exhausted = finalText == null;
        if (exhausted) {
            finalText = TEXT_MAX_ROUNDS;
            buffer = new EditBuffer();
        }
        Kind kind = buffer.isEmpty() ? Kind.NONE
                : buffer.wide() || buffer.touchedWorkIds().size() > cfg.confirmThreshold() ? Kind.PENDING : Kind.APPLY;
        return persistTurn(offerId, pre, caller, text, finalText.isBlank() ? TEXT_APPLIED : finalText, kind,
                buffer, record, writeCalls);
    }

    private TurnResult confirmTurn(Long offerId, Pre pre, Caller caller, String text, Long pendingId) {
        OfferAiChatMessageEntity last = messageDao.findFirstByOfferIdOrderByIdDesc(offerId).orElse(null);
        if (last == null || !last.getId().equals(pendingId) || last.getPendingAction() == null
                || !ROLE_ASSISTANT.equals(last.getRole())
                || !Integer.valueOf(pre.revision()).equals(intOf(last.getPendingAction().get("version")))) {
            throw new ForemenApiException(HttpStatus.CONFLICT, MSG_PENDING_EXPIRED);
        }
        EditBuffer buffer = new EditBuffer();
        ToolContext ctx = new ToolContext(caller.role(), pre.projectId(), offerId, null, pre.snapshot(), buffer);
        List<Map<String, Object>> writeCalls = new ArrayList<>();
        Object stored = last.getPendingAction().get("toolCalls");
        if (stored instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String name = String.valueOf(m.get("name"));
                JsonNode args = mapper.valueToTree(m.get("args"));
                ToolOutput out = registry.run(ctx, name, args);
                if (!out.ok()) {
                    throw new ForemenApiException(HttpStatus.CONFLICT, MSG_PENDING_EXPIRED);
                }
                writeCalls.add(callRecord(new ToolCall(name, args), null));
            }
        }
        if (buffer.isEmpty()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, MSG_PENDING_EXPIRED);
        }
        return persistTurn(offerId, pre, caller, text.isEmpty() ? "estimateai.chat.confirm" : text, TEXT_APPLIED,
                Kind.APPLY, buffer, writeCalls, writeCalls);
    }

    private TurnResult persistTurn(Long offerId, Pre pre, Caller caller, String userText, String assistantText,
                                   Kind kind, EditBuffer buffer, List<Map<String, Object>> record,
                                   List<Map<String, Object>> writeCalls) {
        return tx(false).execute(status -> {
            lockOffer(offerId);
            OfferEntity offer = offerDao.findById(offerId).orElseThrow(OfferAiChatService::notFound);
            assertCurrent(offer, pre);
            OfferAiChatMessageEntity user = newMessage(offer, caller.userId(), ROLE_USER, userText);
            messageDao.save(user);
            OfferAiChatMessageEntity assistant = newMessage(offer, null, ROLE_ASSISTANT, assistantText);
            assistant.setToolCalls(new ArrayList<>(record));
            messageDao.save(assistant);
            entityManager.flush();
            Kind effective = kind;
            boolean applied = false;
            if (kind == Kind.APPLY || kind == Kind.PENDING) {
                EstimateScratchService.ScratchResult result = null;
                try {
                    result = scratch.computeDetailed(pre.projectId(), Seed.snapshot(offerId), buffer.edits(),
                            remap(buffer.metaInputs(), assistant.getId()));
                } catch (ForemenApiException e) {
                    if (!e.getStatus().is4xxClientError()) {
                        throw e;
                    }
                    log.info("Chat turn edits rejected by the engine for offer {}: {}", offerId, e.getMessage());
                    effective = Kind.REJECTED;
                }
                if (result != null) {
                    OfferSnapshot next = withComments(result.snapshot(), buffer.comments());
                    if (kind == Kind.PENDING) {
                        assistant.setContent(TEXT_PENDING);
                        assistant.setPendingAction(pendingAction(offer, pre, buffer, next, writeCalls));
                    } else {
                        assistant.setSnapshotBefore(toMap(offer.getSnapshot()));
                        assistant.setVersionBefore(offer.getRevision());
                        OfferEntity saved = offerService.replaceAiEditedSnapshot(offerId, next, assistant.getId());
                        assistant.setVersionAfter(saved.getRevision());
                        List<Map<String, Object>> calls = new ArrayList<>(record);
                        calls.add(snapshotMarker(saved.getSnapshot()));
                        assistant.setToolCalls(calls);
                        appendChange(saved, assistant.getId(), caller.userId(), "chat.edit",
                                Map.of("lines", buffer.touchedWorkIds().size(), "tools", toolNames(writeCalls)));
                        applied = true;
                    }
                }
            }
            if (effective == Kind.REJECTED) {
                assistant.setContent(TEXT_REJECTED);
            }
            messageDao.save(assistant);
            entityManager.flush();
            OfferEntity fresh = offerDao.findById(offerId).orElseThrow(OfferAiChatService::notFound);
            return new TurnResult(List.of(dto(user), dto(assistant)), applied, summary(fresh), fresh.getRevision());
        });
    }

    // ------------------------------------------------------------------ undo

    /** Restores the snapshot before the latest applied, not yet undone turn (Requirement 11.8). */
    public UndoResult undo(Long offerId) {
        Caller caller = caller();
        Pre pre = preflight(offerId, caller, true);
        Semaphore permit = permits.computeIfAbsent(offerId, k -> new Semaphore(1));
        if (!permit.tryAcquire()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, MSG_BUSY);
        }
        try {
            return tx(false).execute(status -> {
                lockOffer(offerId);
                OfferEntity offer = offerDao.findById(offerId).orElseThrow(OfferAiChatService::notFound);
                assertCurrent(offer, pre);
                OfferAiChatMessageEntity turn = messageDao
                        .findFirstByOfferIdAndVersionAfterIsNotNullAndUndoneAtIsNullOrderByIdDesc(offerId)
                        .orElseThrow(() -> new ForemenApiException(HttpStatus.CONFLICT, MSG_UNDO_STALE));
                if (!turn.getVersionAfter().equals(offer.getRevision())
                        || !markerMatches(turn, offer.getSnapshot()) || turn.getSnapshotBefore() == null) {
                    throw new ForemenApiException(HttpStatus.CONFLICT, MSG_UNDO_STALE);
                }
                OfferSnapshot before = mapper.convertValue(turn.getSnapshotBefore(), OfferSnapshot.class);
                OfferEntity saved = offerService.replaceAiEditedSnapshot(offerId, before, turn.getId(), true);
                turn.setUndoneAt(LocalDateTime.now());
                messageDao.save(turn);
                OfferAiChatMessageEntity system = newMessage(offer, caller.userId(), ROLE_SYSTEM, TEXT_UNDONE);
                system.setVersionBefore(turn.getVersionAfter());
                messageDao.save(system);
                appendChange(saved, turn.getId(), caller.userId(), "chat.undo", Map.of());
                entityManager.flush();
                OfferEntity fresh = offerDao.findById(offerId).orElseThrow(OfferAiChatService::notFound);
                return new UndoResult(List.of(dto(system)), summary(fresh), fresh.getRevision());
            });
        } finally {
            permit.release();
        }
    }

    // ------------------------------------------------------------------ guards

    private Pre preflight(Long offerId, Caller caller, boolean write) {
        offerService.assertOfferAccess(offerId);
        denyClient(caller);
        if (write && (caller.role() == null
                || !evaluator.isAllowed(caller.role(), ToolPermission.ESTIMATE, "UPDATE"))) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, MSG_FORBIDDEN);
        }
        return tx(true).execute(s -> {
            OfferEntity offer = offerDao.findById(offerId).orElseThrow(OfferAiChatService::notFound);
            if (offer.getStatus() != OfferStatus.DRAFT) {
                throw new ForemenApiException(HttpStatus.CONFLICT, MSG_NOT_EDITABLE);
            }
            if (offer.getSnapshot() == null) {
                throw new ForemenApiException(HttpStatus.NOT_FOUND, MSG_SNAPSHOT_MISSING);
            }
            Long projectId = offer.getProject().getId();
            lockGuard.assertUnlocked(projectId);
            return new Pre(projectId, offer.getSnapshot(), offer.getRevision() == null ? 1 : offer.getRevision());
        });
    }

    private void assertRate(Long offerId) {
        long used = messageDao.countByOfferIdAndRoleAndCreatedDateAfter(
                offerId, ROLE_USER, LocalDateTime.now().minusHours(1));
        if (used >= properties.chat().rateLimit()) {
            throw new ForemenApiException(HttpStatus.TOO_MANY_REQUESTS, MSG_RATE);
        }
    }

    /** Transaction-scoped advisory lock (other instances); a held lock is {@code busy}. */
    private void lockOffer(Long offerId) {
        Object got = entityManager.createNativeQuery("SELECT CAST(pg_try_advisory_xact_lock(:k) AS text)")
                .setParameter("k", ADVISORY_NAMESPACE | offerId).getSingleResult();
        if (!"true".equalsIgnoreCase(String.valueOf(got)) && !"t".equalsIgnoreCase(String.valueOf(got))) {
            throw new ForemenApiException(HttpStatus.CONFLICT, MSG_BUSY);
        }
    }

    /** The offer must be unchanged since the turn started (another instance may have written meanwhile). */
    private void assertCurrent(OfferEntity offer, Pre pre) {
        int revision = offer.getRevision() == null ? 1 : offer.getRevision();
        if (offer.getStatus() != OfferStatus.DRAFT) {
            throw new ForemenApiException(HttpStatus.CONFLICT, MSG_NOT_EDITABLE);
        }
        if (revision != pre.revision()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, MSG_BUSY);
        }
        lockGuard.assertUnlocked(pre.projectId());
    }

    private void denyClient(Caller caller) {
        if (CLIENT_ROLE.equals(caller.role())) {
            throw notFound();
        }
    }

    // ------------------------------------------------------------------ helpers

    private TransactionTemplate tx(boolean readOnly) {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setReadOnly(readOnly);
        return t;
    }

    private List<ChatTurn> recentHistory(Long offerId, int turns) {
        List<OfferAiChatMessageEntity> all = tx(true).execute(s -> messageDao.findByOfferIdOrderByIdAsc(offerId));
        List<ChatTurn> out = new ArrayList<>();
        for (OfferAiChatMessageEntity m : all == null ? List.<OfferAiChatMessageEntity>of() : all) {
            if ((ROLE_USER.equals(m.getRole()) || ROLE_ASSISTANT.equals(m.getRole()))
                    && m.getContent() != null && !m.getContent().isBlank()) {
                out.add(new ChatTurn(m.getRole(), m.getContent()));
            }
        }
        int keep = Math.max(2, turns * 2);
        return out.size() <= keep ? out : new ArrayList<>(out.subList(out.size() - keep, out.size()));
    }

    private Map<Long, String> snapshotWorkCodes(OfferSnapshot snapshot) {
        List<Long> ids = new ArrayList<>();
        if (snapshot != null && snapshot.groups() != null) {
            snapshot.groups().forEach(g -> {
                if (g.rows() != null) {
                    g.rows().forEach(r -> ids.add(r.workItemId()));
                }
            });
        }
        return ids.isEmpty() ? Map.of() : catalog.workCodes(ids);
    }

    private OfferAiChatMessageEntity newMessage(OfferEntity offer, Long userId, String role, String content) {
        OfferAiChatMessageEntity m = new OfferAiChatMessageEntity();
        m.setOffer(offer);
        m.setUserId(userId);
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private Map<String, Object> pendingAction(OfferEntity offer, Pre pre, EditBuffer buffer, OfferSnapshot next,
                                              List<Map<String, Object>> writeCalls) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("version", pre.revision());
        p.put("affectedLines", buffer.touchedWorkIds().size());
        p.put("wide", buffer.wide());
        p.put("totalNetBefore", offer.getTotalNet());
        p.put("totalNetAfter", next.totalNet());
        p.put("toolCalls", new ArrayList<>(writeCalls));
        return p;
    }

    private Map<String, Object> callRecord(ToolCall call, ToolOutput out) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", call.name());
        m.put("args", call.args() == null ? Map.of() : mapper.convertValue(call.args(), Map.class));
        if (out != null) {
            m.put("ok", out.ok());
            if (!out.ok()) {
                m.put("error", out.error());
            }
        }
        return m;
    }

    private static List<String> toolNames(List<Map<String, Object>> calls) {
        return calls.stream().map(c -> String.valueOf(c.get("name"))).distinct().toList();
    }

    private static List<CellMetaInput> remap(List<CellMetaInput> in, Long turnId) {
        return in.stream().map(m -> new CellMetaInput(m.workCode(), m.roomId(), m.origin(), turnId,
                m.rationale(), m.volumeReason(), m.status(), m.confidence(), m.sourceFile(), m.sourcePage(),
                m.priceReason(), m.volumeOverridden())).toList();
    }

    /** Marker row in {@code tool_calls}: the {@code capturedAt} of the snapshot this turn produced (undo staleness). */
    private static Map<String, Object> snapshotMarker(OfferSnapshot snapshot) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "snapshot");
        m.put("capturedAt", snapshot == null ? null : snapshot.capturedAt());
        return m;
    }

    private static boolean markerMatches(OfferAiChatMessageEntity turn, OfferSnapshot current) {
        if (turn.getToolCalls() == null || current == null) {
            return false;
        }
        for (Map<String, Object> c : turn.getToolCalls()) {
            if ("snapshot".equals(c.get("type"))) {
                return c.get("capturedAt") != null && c.get("capturedAt").equals(current.capturedAt());
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(OfferSnapshot snapshot) {
        return mapper.convertValue(snapshot, Map.class);
    }

    /** Appends a {@code changes[]} entry to the AI report (Requirement 11.9); manual offers have no report. */
    @SuppressWarnings("unchecked")
    private void appendChange(OfferEntity offer, Long turnId, Long userId, String summaryCode,
                              Map<String, Object> params) {
        if (!offer.isAiGenerated()) {
            return;
        }
        Map<String, Object> report = offer.getAiReport() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(offer.getAiReport());
        List<Object> changes = report.get("changes") instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("turnId", turnId);
        entry.put("userId", userId);
        entry.put("at", LocalDateTime.now().toString());
        entry.put("summaryCode", summaryCode);
        entry.put("params", params);
        changes.add(entry);
        report.put("changes", changes);
        offer.setAiReport(report);
        offerDao.save(offer);
    }

    /** Sets the buffered line comments on the matching rows of the resulting snapshot. */
    static OfferSnapshot withComments(OfferSnapshot s, List<EditBuffer.LineComment> comments) {
        if (comments == null || comments.isEmpty() || s.groups() == null) {
            return s;
        }
        Map<Long, String> byWork = new LinkedHashMap<>();
        for (EditBuffer.LineComment c : comments) {
            byWork.put(c.workItemId(), c.text() == null || c.text().isBlank() ? null : c.text());
        }
        List<OfferSnapshot.Group> groups = new ArrayList<>();
        for (OfferSnapshot.Group g : s.groups()) {
            List<OfferSnapshot.Row> rows = g.rows() == null ? null : g.rows().stream()
                    .map(r -> byWork.containsKey(r.workItemId()) ? withComment(r, byWork.get(r.workItemId())) : r)
                    .toList();
            groups.add(new OfferSnapshot.Group(g.categoryId(), g.nameRu(), g.namePl(), rows));
        }
        return new OfferSnapshot(s.version(), s.capturedAt(), s.vatRatePct(), s.totalNet(), s.totalVat(),
                s.totalGross(), s.rooms(), groups, s.appliedPackageCode(), s.appliedPackageNameRu(),
                s.appliedPackageNamePl(), s.packageSubtotal());
    }

    private static OfferSnapshot.Row withComment(OfferSnapshot.Row r, String comment) {
        return new OfferSnapshot.Row(r.workItemId(), r.nameRu(), r.namePl(), r.unitCode(), r.unitNameRu(),
                r.unitNamePl(), r.quantity(), r.unitPrice(), r.baseUnitPrice(), r.valueNet(), r.baseValueNet(),
                r.cells(), r.unitPriceOverride(), comment, r.workPriceId(), r.lineNo(), r.lineId(),
                r.packageCovered());
    }

    private MessageDto dto(OfferAiChatMessageEntity m) {
        Map<String, Object> pending = null;
        if (m.getPendingAction() != null) {
            pending = new LinkedHashMap<>(m.getPendingAction());
            pending.put("id", m.getId());
        }
        List<Map<String, Object>> calls = m.getToolCalls() == null ? null : m.getToolCalls().stream()
                .filter(c -> c.get("name") != null).toList();
        return new MessageDto(m.getId(), m.getRole(), m.getContent(), calls, pending, m.getVersionBefore(),
                m.getVersionAfter(), m.getUndoneAt() != null, m.getCreatedDate());
    }

    private OfferSummary summary(OfferEntity o) {
        return new OfferSummary(o.getId(), o.getStatus() == null ? null : o.getStatus().name(), o.getRevision(),
                o.getTotalNet(), o.getTotalVat(), o.getTotalGross());
    }

    private static Integer intOf(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static ForemenApiException notFound() {
        return new ForemenApiException(HttpStatus.NOT_FOUND, MSG_NOT_FOUND);
    }

    private static ForemenApiException unavailable() {
        return new ForemenApiException(HttpStatus.SERVICE_UNAVAILABLE, EstimateAiErrors.UNAVAILABLE);
    }

    private static Caller caller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw notFound();
        }
        Long userId = null;
        try {
            userId = Long.valueOf(auth.getName());
        } catch (NumberFormatException ignored) {
            // non-numeric principal (tests): no user id
        }
        String role = null;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (a.getAuthority() != null && a.getAuthority().startsWith("ROLE_")) {
                role = a.getAuthority().substring("ROLE_".length());
                break;
            }
        }
        return new Caller(userId, role);
    }
}
