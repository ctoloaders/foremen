package com.foremen.service.estimateai.chat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.service.estimate.CellMetaInput;
import com.foremen.service.estimate.EstimateAssignmentService.EditKind;
import com.foremen.service.estimate.EstimateAssignmentService.StagedEdit;
import com.foremen.service.estimate.LabourPriceAdjustmentScope;
import com.foremen.service.estimateai.chat.ChatCatalog.RoomInfo;
import com.foremen.service.estimateai.chat.ChatCatalog.WorkInfo;

/**
 * FOR-05-04 (Requirements 11.1, 11.2, 11.4, 21.5) — the seven chat tools as thin adapters over the same
 * {@link StagedEdit}s the manual paths build. Instantiated only by {@link OfferChatToolRegistry}.
 *
 * <p>Every write tool requires a non-blank {@code reason}; without it the call is a tool error. A write
 * tool validates, appends edits and {@link CellMetaInput}s to the turn's {@link EditBuffer} and returns a
 * short result; validation failures are tool results, never exceptions. Materials are never touched.</p>
 */
final class OfferChatTools {

    static final String ORIGIN_CHAT = "CHAT";
    static final String STATUS_CONFIRMED = "CONFIRMED";
    static final int MAX_REASON = 500;
    static final int MAX_COMMENT = 1024;
    static final int FIND_LIMIT = 20;
    static final BigDecimal PCT_MIN = BigDecimal.valueOf(-100);
    static final BigDecimal PCT_MAX = BigDecimal.valueOf(1000);

    static final String ERR_REASON = "error.estimateai.tool.reason_required";
    static final String ERR_ARGS = "error.estimateai.tool.invalid_arguments";
    static final String ERR_WORK = "error.estimateai.tool.unknown_work";
    static final String ERR_ROOM = "error.estimateai.tool.unknown_room";
    static final String ERR_NO_PRICE = "error.estimateai.tool.no_price";
    static final String ERR_QTY = "error.estimateai.tool.invalid_quantity";
    static final String ERR_PCT = "error.estimateai.tool.invalid_pct";
    static final String ERR_SCOPE = "error.estimateai.tool.invalid_scope";
    static final String ERR_TARGET = "error.estimateai.tool.unknown_target";
    static final String ERR_NOT_IN_OFFER = "error.estimateai.tool.work_not_in_offer";
    static final String ERR_CELL = "error.estimateai.tool.cell_not_found";
    static final String ERR_NO_ROOMS = "error.estimateai.tool.no_matching_rooms";
    static final String ERR_NO_LINES = "error.estimateai.tool.no_lines_in_scope";

    private OfferChatTools() {
    }

    static List<OfferChatTool> create(ChatCatalog catalog, ObjectMapper mapper) {
        return List.of(
                new FindWork(catalog, mapper),
                new GetOfferState(catalog, mapper),
                new AssignWork(catalog, mapper),
                new UnassignWork(catalog, mapper),
                new SetVolume(catalog, mapper),
                new ClearVolumeOverride(catalog, mapper),
                new AdjustPrice(catalog, mapper),
                new SetLineComment(catalog, mapper));
    }

    // ------------------------------------------------------------------ base

    abstract static class Base implements OfferChatTool {
        final ChatCatalog catalog;
        final ObjectMapper mapper;

        Base(ChatCatalog catalog, ObjectMapper mapper) {
            this.catalog = catalog;
            this.mapper = mapper;
        }

        ObjectNode schema() {
            ObjectNode n = mapper.createObjectNode();
            n.put("type", "object");
            n.set("properties", mapper.createObjectNode());
            return n;
        }

        ObjectNode prop(ObjectNode schema, String name, String type, String description) {
            ObjectNode p = mapper.createObjectNode();
            p.put("type", type);
            p.put("description", description);
            ((ObjectNode) schema.get("properties")).set(name, p);
            return p;
        }

        void arrayOfIntegers(ObjectNode schema, String name, String description) {
            ObjectNode p = prop(schema, name, "array", description);
            p.set("items", mapper.createObjectNode().put("type", "integer"));
        }

        void required(ObjectNode schema, String... names) {
            ArrayNode req = mapper.createArrayNode();
            for (String n : names) {
                req.add(n);
            }
            schema.set("required", req);
        }

        void reasonProp(ObjectNode schema) {
            prop(schema, "reason", "string", "Mandatory. Why this change is made, quoting the user's instruction. "
                    + "Becomes the explanation of the changed cells. Write it in the user's language.");
        }

        static String text(JsonNode args, String field) {
            JsonNode n = args == null ? null : args.get(field);
            if (n == null || n.isNull() || !n.isValueNode()) {
                return null;
            }
            String s = n.asText().trim();
            return s.isEmpty() ? null : s;
        }

        /** The trimmed reason, cut to {@link #MAX_REASON} chars; {@code null} when missing. */
        static String reason(JsonNode args) {
            String r = text(args, "reason");
            return r == null ? null : (r.length() > MAX_REASON ? r.substring(0, MAX_REASON) : r);
        }

        ToolOutput done(ObjectNode data) {
            return ToolOutput.ok(data);
        }

        /** Resolves an active, priced-or-not work by code. */
        Optional<WorkInfo> activeWork(String code) {
            return catalog.findByCode(code).filter(WorkInfo::active);
        }

        static Long longValue(JsonNode n) {
            if (n == null || !n.isNumber() && !n.isTextual()) {
                return null;
            }
            try {
                return n.isNumber() ? (n.canConvertToLong() && n.asDouble() == n.asLong() ? n.asLong() : null)
                        : Long.valueOf(n.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        static BigDecimal decimal(JsonNode n) {
            if (n == null || !n.isNumber() && !n.isTextual()) {
                return null;
            }
            try {
                return n.isNumber() ? n.decimalValue() : new BigDecimal(n.asText().trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        CellMetaInput meta(String workCode, Long roomId, String rationale, String volumeReason, String priceReason,
                           boolean volumeOverridden, Long turnId) {
            return new CellMetaInput(workCode, roomId, ORIGIN_CHAT, turnId, rationale, volumeReason,
                    STATUS_CONFIRMED, null, null, null, priceReason, volumeOverridden);
        }

        ArrayNode ids(Iterable<Long> values) {
            ArrayNode a = mapper.createArrayNode();
            values.forEach(a::add);
            return a;
        }
    }

    // ------------------------------------------------------------- read tools

    static final class FindWork extends Base {
        FindWork(ChatCatalog catalog, ObjectMapper mapper) {
            super(catalog, mapper);
        }

        @Override
        public String name() {
            return "find_work";
        }

        @Override
        public String description() {
            return "Search the work catalog by code or name (RU/PL), optionally within one category. "
                    + "Returns code, names, unit, category, whether it is active and has a price.";
        }

        @Override
        public JsonNode params() {
            ObjectNode s = schema();
            prop(s, "query", "string", "Part of the work code or name.");
            prop(s, "categoryCode", "string", "Optional work category code to search within.");
            return s;
        }

        @Override
        public ToolPermission permission() {
            return ToolPermission.READ;
        }

        @Override
        public ToolOutput run(ToolContext ctx, JsonNode args) {
            String query = text(args, "query");
            String category = text(args, "categoryCode");
            if (query == null && category == null) {
                return ToolOutput.error(ERR_ARGS);
            }
            ArrayNode works = mapper.createArrayNode();
            for (WorkInfo w : catalog.search(query, category, FIND_LIMIT)) {
                ObjectNode n = works.addObject();
                n.put("code", w.code());
                n.put("nameRu", w.nameRu());
                n.put("namePl", w.namePl());
                n.put("unit", w.unit());
                n.put("categoryCode", w.categoryCode());
                n.put("active", w.active());
                n.put("hasPrice", w.hasPrice());
            }
            ObjectNode out = mapper.createObjectNode();
            out.set("works", works);
            return done(out);
        }
    }

    static final class GetOfferState extends Base {
        GetOfferState(ChatCatalog catalog, ObjectMapper mapper) {
            super(catalog, mapper);
        }

        @Override
        public String name() {
            return "get_offer_state";
        }

        @Override
        public String description() {
            return "Compact text view of the offer: rooms, rows with quantities per room, prices and totals. "
                    + "Use the optional filter (work code, name, category or room) to narrow it.";
        }

        @Override
        public JsonNode params() {
            ObjectNode s = schema();
            prop(s, "filter", "string", "Optional text matched against work code/name, category and room labels.");
            return s;
        }

        @Override
        public ToolPermission permission() {
            return ToolPermission.READ;
        }

        @Override
        public ToolOutput run(ToolContext ctx, JsonNode args) {
            Set<Long> ids = new LinkedHashSet<>(ctx.snapshotCategories().keySet());
            String state = SnapshotTextRenderer.render(ctx.snapshot(), catalog.workCodes(ids),
                    text(args, "filter"), SnapshotTextRenderer.DEFAULT_MAX_CHARS);
            ObjectNode out = mapper.createObjectNode();
            out.put("state", state);
            return done(out);
        }
    }

    // ------------------------------------------------------------ write tools

    static final class AssignWork extends Base {
        AssignWork(ChatCatalog catalog, ObjectMapper mapper) {
            super(catalog, mapper);
        }

        @Override
        public String name() {
            return "assign_work";
        }

        @Override
        public String description() {
            return "Assign a catalog work to rooms; the volume is computed by the room formula. Give roomIds "
                    + "or set allMatching=true for every room the work applies to.";
        }

        @Override
        public JsonNode params() {
            ObjectNode s = schema();
            prop(s, "workCode", "string", "Catalog work code.");
            arrayOfIntegers(s, "roomIds", "Room ids from the offer state.");
            prop(s, "allMatching", "boolean", "true to use every project room the work applies to.");
            reasonProp(s);
            required(s, "workCode", "reason");
            return s;
        }

        @Override
        public ToolPermission permission() {
            return ToolPermission.WRITE;
        }

        @Override
        public ToolOutput run(ToolContext ctx, JsonNode args) {
            String reason = reason(args);
            if (reason == null) {
                return ToolOutput.error(ERR_REASON);
            }
            String code = text(args, "workCode");
            if (code == null) {
                return ToolOutput.error(ERR_ARGS);
            }
            Optional<WorkInfo> work = activeWork(code);
            if (work.isEmpty()) {
                return ToolOutput.error(ERR_WORK);
            }
            if (!work.get().hasPrice()) {
                return ToolOutput.error(ERR_NO_PRICE);
            }
            List<RoomInfo> projectRooms = catalog.projectRooms(ctx.projectId());
            JsonNode roomIdsNode = args.get("roomIds");
            boolean allMatching = args.path("allMatching").asBoolean(false)
                    || roomIdsNode != null && roomIdsNode.isTextual()
                    && "all_matching".equalsIgnoreCase(roomIdsNode.asText().trim());
            LinkedHashSet<Long> rooms = new LinkedHashSet<>();
            if (allMatching) {
                for (RoomInfo r : projectRooms) {
                    if (work.get().roomTypeIds().isEmpty() || work.get().roomTypeIds().contains(r.roomTypeId())) {
                        rooms.add(r.id());
                    }
                }
                if (rooms.isEmpty()) {
                    return ToolOutput.error(ERR_NO_ROOMS);
                }
            } else {
                if (roomIdsNode == null || !roomIdsNode.isArray() || roomIdsNode.isEmpty()) {
                    return ToolOutput.error(ERR_ARGS);
                }
                Set<Long> known = new LinkedHashSet<>();
                projectRooms.forEach(r -> known.add(r.id()));
                for (JsonNode n : roomIdsNode) {
                    Long id = longValue(n);
                    if (id == null || !known.contains(id)) {
                        return ToolOutput.error(ERR_ROOM);
                    }
                    rooms.add(id);
                }
            }
            String packageCode = ctx.snapshot() == null ? null : ctx.snapshot().appliedPackageCode();
            List<Long> assigned = new ArrayList<>();
            List<Long> existing = new ArrayList<>();
            for (Long roomId : rooms) {
                if (ctx.hasCell(work.get().id(), roomId)) {
                    existing.add(roomId);
                    continue;
                }
                ctx.buffer().add(StagedEdit.assign(work.get().id(), roomId, packageCode));
                ctx.buffer().cellAssigned(work.get().id(), roomId, work.get().categoryId());
                ctx.buffer().putMeta(meta(work.get().code(), roomId, reason, null, null, false, ctx.turnId()));
                assigned.add(roomId);
            }
            if (!assigned.isEmpty()) {
                ctx.buffer().touch(work.get().id());
            }
            ObjectNode out = mapper.createObjectNode();
            out.put("workCode", work.get().code());
            out.set("assignedRoomIds", ids(assigned));
            out.set("alreadyAssignedRoomIds", ids(existing));
            return done(out);
        }
    }

    static final class UnassignWork extends Base {
        UnassignWork(ChatCatalog catalog, ObjectMapper mapper) {
            super(catalog, mapper);
        }

        @Override
        public String name() {
            return "unassign_work";
        }

        @Override
        public String description() {
            return "Remove a work from rooms. Give roomIds or set all=true to remove it from every room.";
        }

        @Override
        public JsonNode params() {
            ObjectNode s = schema();
            prop(s, "workCode", "string", "Catalog work code.");
            arrayOfIntegers(s, "roomIds", "Room ids to remove the work from.");
            prop(s, "all", "boolean", "true to remove the work from every room.");
            reasonProp(s);
            required(s, "workCode", "reason");
            return s;
        }

        @Override
        public ToolPermission permission() {
            return ToolPermission.WRITE;
        }

        @Override
        public ToolOutput run(ToolContext ctx, JsonNode args) {
            String reason = reason(args);
            if (reason == null) {
                return ToolOutput.error(ERR_REASON);
            }
            String code = text(args, "workCode");
            if (code == null) {
                return ToolOutput.error(ERR_ARGS);
            }
            Optional<WorkInfo> work = catalog.findByCode(code);
            if (work.isEmpty()) {
                return ToolOutput.error(ERR_WORK);
            }
            Set<Long> current = ctx.currentCells().getOrDefault(work.get().id(), Set.of());
            if (current.isEmpty()) {
                return ToolOutput.error(ERR_NOT_IN_OFFER);
            }
            JsonNode roomIdsNode = args.get("roomIds");
            boolean all = args.path("all").asBoolean(false)
                    || roomIdsNode != null && roomIdsNode.isTextual()
                    && "all".equalsIgnoreCase(roomIdsNode.asText().trim());
            LinkedHashSet<Long> rooms = new LinkedHashSet<>();
            if (all) {
                rooms.addAll(current);
            } else {
                if (roomIdsNode == null || !roomIdsNode.isArray() || roomIdsNode.isEmpty()) {
                    return ToolOutput.error(ERR_ARGS);
                }
                for (JsonNode n : roomIdsNode) {
                    Long id = longValue(n);
                    if (id == null) {
                        return ToolOutput.error(ERR_ARGS);
                    }
                    if (!current.contains(id)) {
                        return ToolOutput.error(ERR_CELL);
                    }
                    rooms.add(id);
                }
            }
            for (Long roomId : rooms) {
                ctx.buffer().add(StagedEdit.unassign(work.get().id(), roomId));
                ctx.buffer().cellRemoved(work.get().id(), roomId);
            }
            ctx.buffer().touch(work.get().id());
            ObjectNode out = mapper.createObjectNode();
            out.put("workCode", work.get().code());
            out.set("removedRoomIds", ids(rooms));
            return done(out);
        }
    }

    static final class SetVolume extends Base {
        SetVolume(ChatCatalog catalog, ObjectMapper mapper) {
            super(catalog, mapper);
        }

        @Override
        public String name() {
            return "set_volume";
        }

        @Override
        public String description() {
            return "Override the volume of one work in one room with a manual positive quantity "
                    + "(in the work's unit).";
        }

        @Override
        public JsonNode params() {
            ObjectNode s = schema();
            prop(s, "workCode", "string", "Catalog work code.");
            prop(s, "roomId", "integer", "Room id from the offer state.");
            prop(s, "quantity", "number", "New volume, greater than zero.");
            reasonProp(s);
            required(s, "workCode", "roomId", "quantity", "reason");
            return s;
        }

        @Override
        public ToolPermission permission() {
            return ToolPermission.WRITE;
        }

        @Override
        public ToolOutput run(ToolContext ctx, JsonNode args) {
            String reason = reason(args);
            if (reason == null) {
                return ToolOutput.error(ERR_REASON);
            }
            Optional<WorkInfo> work = activeWork(String.valueOf(text(args, "workCode")));
            if (work.isEmpty()) {
                return ToolOutput.error(ERR_WORK);
            }
            Long roomId = longValue(args.get("roomId"));
            if (roomId == null) {
                return ToolOutput.error(ERR_ARGS);
            }
            if (!isProjectRoom(ctx, roomId)) {
                return ToolOutput.error(ERR_ROOM);
            }
            BigDecimal qty = decimal(args.get("quantity"));
            if (qty == null || qty.signum() <= 0) {
                return ToolOutput.error(ERR_QTY);
            }
            if (!ctx.hasCell(work.get().id(), roomId)) {
                return ToolOutput.error(ERR_CELL);
            }
            ctx.buffer().add(new StagedEdit(EditKind.SET_QUANTITY, work.get().id(), roomId, null, null, null,
                    null, null, null, qty));
            ctx.buffer().touch(work.get().id());
            ctx.buffer().putMeta(meta(work.get().code(), roomId, reason, reason, null, true, ctx.turnId()));
            ObjectNode out = mapper.createObjectNode();
            out.put("workCode", work.get().code());
            out.put("roomId", roomId);
            out.put("quantity", qty);
            return done(out);
        }

        private boolean isProjectRoom(ToolContext ctx, Long roomId) {
            return catalog.projectRooms(ctx.projectId()).stream().anyMatch(r -> roomId.equals(r.id()));
        }
    }

    static final class ClearVolumeOverride extends Base {
        ClearVolumeOverride(ChatCatalog catalog, ObjectMapper mapper) {
            super(catalog, mapper);
        }

        @Override
        public String name() {
            return "clear_volume_override";
        }

        @Override
        public String description() {
            return "Remove the manual volume override of one work in one room, returning to the room formula.";
        }

        @Override
        public JsonNode params() {
            ObjectNode s = schema();
            prop(s, "workCode", "string", "Catalog work code.");
            prop(s, "roomId", "integer", "Room id from the offer state.");
            reasonProp(s);
            required(s, "workCode", "roomId", "reason");
            return s;
        }

        @Override
        public ToolPermission permission() {
            return ToolPermission.WRITE;
        }

        @Override
        public ToolOutput run(ToolContext ctx, JsonNode args) {
            String reason = reason(args);
            if (reason == null) {
                return ToolOutput.error(ERR_REASON);
            }
            Optional<WorkInfo> work = catalog.findByCode(String.valueOf(text(args, "workCode")));
            if (work.isEmpty()) {
                return ToolOutput.error(ERR_WORK);
            }
            Long roomId = longValue(args.get("roomId"));
            if (roomId == null) {
                return ToolOutput.error(ERR_ARGS);
            }
            if (catalog.projectRooms(ctx.projectId()).stream().noneMatch(r -> roomId.equals(r.id()))) {
                return ToolOutput.error(ERR_ROOM);
            }
            if (!ctx.hasCell(work.get().id(), roomId)) {
                return ToolOutput.error(ERR_CELL);
            }
            ctx.buffer().add(new StagedEdit(EditKind.CLEAR_QUANTITY, work.get().id(), roomId, null, null, null,
                    null, null, null, null));
            ctx.buffer().touch(work.get().id());
            ctx.buffer().putMeta(meta(work.get().code(), roomId, reason, null, null, false, ctx.turnId()));
            ObjectNode out = mapper.createObjectNode();
            out.put("workCode", work.get().code());
            out.put("roomId", roomId);
            return done(out);
        }
    }

    static final class AdjustPrice extends Base {
        AdjustPrice(ChatCatalog catalog, ObjectMapper mapper) {
            super(catalog, mapper);
        }

        @Override
        public String name() {
            return "adjust_price";
        }

        @Override
        public String description() {
            return "Adjust the labour unit price by a signed percentage (markup > 0, discount < 0, range "
                    + "-100..1000) for the whole project, one work category or one work.";
        }

        @Override
        public JsonNode params() {
            ObjectNode s = schema();
            ObjectNode scope = prop(s, "scope", "string", "PROJECT, WORK_CATEGORY or WORK_ITEM.");
            ArrayNode values = mapper.createArrayNode();
            for (LabourPriceAdjustmentScope v : LabourPriceAdjustmentScope.values()) {
                values.add(v.name());
            }
            scope.set("enum", values);
            prop(s, "targetCode", "string", "Category code (WORK_CATEGORY) or work code (WORK_ITEM); omit for PROJECT.");
            prop(s, "pct", "number", "Signed percentage, between -100 and 1000.");
            reasonProp(s);
            required(s, "scope", "pct", "reason");
            return s;
        }

        @Override
        public ToolPermission permission() {
            return ToolPermission.WRITE;
        }

        @Override
        public ToolOutput run(ToolContext ctx, JsonNode args) {
            String reason = reason(args);
            if (reason == null) {
                return ToolOutput.error(ERR_REASON);
            }
            LabourPriceAdjustmentScope scope;
            try {
                scope = LabourPriceAdjustmentScope.valueOf(String.valueOf(text(args, "scope")).toUpperCase());
            } catch (IllegalArgumentException e) {
                return ToolOutput.error(ERR_SCOPE);
            }
            BigDecimal pct = decimal(args.get("pct"));
            if (pct == null || pct.compareTo(PCT_MIN) < 0 || pct.compareTo(PCT_MAX) > 0) {
                return ToolOutput.error(ERR_PCT);
            }
            Long targetId = null;
            String targetCode = text(args, "targetCode");
            if (scope == LabourPriceAdjustmentScope.WORK_CATEGORY) {
                targetId = targetCode == null ? null : catalog.categoryIdByCode(targetCode).orElse(null);
                if (targetId == null) {
                    return ToolOutput.error(ERR_TARGET);
                }
            } else if (scope == LabourPriceAdjustmentScope.WORK_ITEM) {
                targetId = targetCode == null ? null : catalog.findByCode(targetCode).map(WorkInfo::id).orElse(null);
                if (targetId == null) {
                    return ToolOutput.error(ERR_TARGET);
                }
            }
            Map<Long, Set<Long>> inScope = linesInScope(ctx, scope, targetId);
            if (inScope.isEmpty()) {
                return ToolOutput.error(ERR_NO_LINES);
            }
            ctx.buffer().add(StagedEdit.adjustLabourPrice(scope, targetId, pct));
            Map<Long, String> codes = catalog.workCodes(inScope.keySet());
            for (Map.Entry<Long, Set<Long>> e : inScope.entrySet()) {
                ctx.buffer().touch(e.getKey());
                String code = codes.get(e.getKey());
                if (code == null) {
                    continue;
                }
                for (Long roomId : e.getValue()) {
                    ctx.buffer().putMeta(meta(code, roomId, null, null, reason, false, ctx.turnId()));
                }
            }
            if (scope == LabourPriceAdjustmentScope.PROJECT) {
                ctx.buffer().markWide();
            }
            ObjectNode out = mapper.createObjectNode();
            out.put("scope", scope.name());
            out.put("pct", pct);
            out.put("linesAffected", inScope.size());
            return done(out);
        }

        /** Work item id to its cell rooms, for the lines of the current state (snapshot + turn edits) in scope. */
        private Map<Long, Set<Long>> linesInScope(ToolContext ctx, LabourPriceAdjustmentScope scope, Long targetId) {
            Map<Long, Long> categories = new LinkedHashMap<>(ctx.snapshotCategories());
            ctx.buffer().cellStates().keySet().forEach(k -> {
                Long w = Long.valueOf(k.split(":")[0]);
                Long c = ctx.buffer().categoryOf(w);
                if (c != null) {
                    categories.putIfAbsent(w, c);
                }
            });
            Map<Long, Set<Long>> out = new LinkedHashMap<>();
            ctx.currentCells().forEach((w, rooms) -> {
                if (rooms.isEmpty()) {
                    return;
                }
                boolean in = switch (scope) {
                    case PROJECT -> true;
                    case WORK_ITEM -> w.equals(targetId);
                    case WORK_CATEGORY -> targetId.equals(categories.get(w));
                };
                if (in) {
                    out.put(w, new TreeSet<>(rooms));
                }
            });
            return out;
        }
    }

    static final class SetLineComment extends Base {
        SetLineComment(ChatCatalog catalog, ObjectMapper mapper) {
            super(catalog, mapper);
        }

        @Override
        public String name() {
            return "set_line_comment";
        }

        @Override
        public String description() {
            return "Set the free-text comment of a work's line (empty text clears it).";
        }

        @Override
        public JsonNode params() {
            ObjectNode s = schema();
            prop(s, "workCode", "string", "Catalog work code of a line that is in the offer.");
            prop(s, "text", "string", "Comment text, up to " + MAX_COMMENT + " characters; empty clears it.");
            reasonProp(s);
            required(s, "workCode", "text", "reason");
            return s;
        }

        @Override
        public ToolPermission permission() {
            return ToolPermission.WRITE;
        }

        @Override
        public ToolOutput run(ToolContext ctx, JsonNode args) {
            String reason = reason(args);
            if (reason == null) {
                return ToolOutput.error(ERR_REASON);
            }
            Optional<WorkInfo> work = catalog.findByCode(String.valueOf(text(args, "workCode")));
            if (work.isEmpty()) {
                return ToolOutput.error(ERR_WORK);
            }
            JsonNode t = args.get("text");
            if (t == null || !t.isTextual()) {
                return ToolOutput.error(ERR_ARGS);
            }
            String comment = t.asText().trim();
            if (comment.length() > MAX_COMMENT) {
                return ToolOutput.error(ERR_ARGS);
            }
            if (ctx.currentCells().getOrDefault(work.get().id(), Set.of()).isEmpty()) {
                return ToolOutput.error(ERR_NOT_IN_OFFER);
            }
            ctx.buffer().addComment(new EditBuffer.LineComment(work.get().id(), work.get().code(), comment, reason));
            ctx.buffer().touch(work.get().id());
            ObjectNode out = mapper.createObjectNode();
            out.put("workCode", work.get().code());
            return done(out);
        }
    }
}
