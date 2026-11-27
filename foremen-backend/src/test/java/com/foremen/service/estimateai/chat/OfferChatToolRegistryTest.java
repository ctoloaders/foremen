package com.foremen.service.estimateai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.service.estimate.CellAiMeta;
import com.foremen.service.estimate.CellMetaInput;
import com.foremen.service.estimate.EstimateAssignmentService.EditKind;
import com.foremen.service.estimate.EstimateAssignmentService.StagedEdit;
import com.foremen.service.estimate.LabourPriceAdjustmentScope;
import com.foremen.service.estimateai.chat.ChatCatalog.RoomInfo;
import com.foremen.service.estimateai.chat.ChatCatalog.WorkInfo;
import com.foremen.service.estimateai.ai.OfferChatClient;
import com.foremen.service.offer.OfferSnapshot;
import com.foremen.service.permission.ForemenPermissionEvaluator;

/** FOR-05-04 task 4.1 — registry, the eight tools and the snapshot renderer (Requirements 11.1 to 11.4, 21.5). */
class OfferChatToolRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Long PROJECT = 1L;

    private final Map<String, WorkInfo> works = new LinkedHashMap<>();
    private final List<RoomInfo> rooms = new ArrayList<>();
    private ForemenPermissionEvaluator evaluator;
    private OfferChatToolRegistry registry;

    /** In-memory catalog (no mocks): the same contract as {@link JpaChatCatalog}. */
    private final ChatCatalog catalog = new ChatCatalog() {
        @Override
        public List<WorkInfo> search(String query, String categoryCode, int limit) {
            String q = query == null ? "" : query.toLowerCase();
            return works.values().stream()
                    .filter(w -> categoryCode == null || categoryCode.equals(w.categoryCode()))
                    .filter(w -> q.isEmpty() || w.code().toLowerCase().contains(q)
                            || w.nameRu().toLowerCase().contains(q))
                    .limit(limit).toList();
        }

        @Override
        public Optional<WorkInfo> findByCode(String code) {
            return Optional.ofNullable(code == null ? null : works.get(code));
        }

        @Override
        public Optional<WorkInfo> findById(Long id) {
            return works.values().stream().filter(w -> w.id().equals(id)).findFirst();
        }

        @Override
        public Optional<Long> categoryIdByCode(String categoryCode) {
            return works.values().stream().filter(w -> categoryCode.equals(w.categoryCode()))
                    .map(WorkInfo::categoryId).findFirst();
        }

        @Override
        public Map<Long, String> workCodes(Collection<Long> ids) {
            Map<Long, String> out = new LinkedHashMap<>();
            works.values().stream().filter(w -> ids.contains(w.id())).forEach(w -> out.put(w.id(), w.code()));
            return out;
        }

        @Override
        public List<RoomInfo> projectRooms(Long projectId) {
            return rooms;
        }
    };

    @BeforeEach
    void setUp() {
        works.put("TILE", new WorkInfo(10L, "TILE", "Плитка", "Płytki", "m2", true, true, 100L, "FIN", Set.of(1L)));
        works.put("PAINT", new WorkInfo(11L, "PAINT", "Покраска", "Malowanie", "m2", true, true, 100L, "FIN", Set.of()));
        works.put("WIRE", new WorkInfo(12L, "WIRE", "Проводка", "Okablowanie", "pcs", true, true, 200L, "ELE", Set.of()));
        works.put("OLD", new WorkInfo(13L, "OLD", "Старое", "Stare", "m2", false, true, 100L, "FIN", Set.of()));
        works.put("FREE", new WorkInfo(14L, "FREE", "Без цены", "Bez ceny", "m2", true, false, 100L, "FIN", Set.of()));
        rooms.add(new RoomInfo(501L, "Bathroom", 1L));
        rooms.add(new RoomInfo(502L, "Kitchen", 2L));
        evaluator = mock(ForemenPermissionEvaluator.class);
        when(evaluator.isAllowed(eq("EDITOR"), any(), any())).thenReturn(true);
        when(evaluator.isAllowed(eq("VIEWER"), eq("OFFERS"), eq("READ"))).thenReturn(true);
        registry = new OfferChatToolRegistry(catalog, evaluator, MAPPER);
    }

    private OfferSnapshot snapshot() {
        OfferSnapshot.Row tile = row(10L, "Плитка", "50", List.of(
                new OfferSnapshot.Cell(501L, new BigDecimal("12.4"))));
        OfferSnapshot.Row paint = row(11L, "Покраска", "20", List.of(
                new OfferSnapshot.Cell(501L, new BigDecimal("30")), new OfferSnapshot.Cell(502L, new BigDecimal("25"))));
        OfferSnapshot.Row wire = row(12L, "Проводка", "10", List.of(
                new OfferSnapshot.Cell(502L, new BigDecimal("4"))));
        return new OfferSnapshot(3, "2026-01-01T00:00:00Z", new BigDecimal("23"), new BigDecimal("1000"),
                new BigDecimal("230"), new BigDecimal("1230"),
                List.of(new OfferSnapshot.Room(501L, "Bathroom"), new OfferSnapshot.Room(502L, "Kitchen")),
                List.of(new OfferSnapshot.Group(100L, "Отделка", "Wykończenie", List.of(tile, paint)),
                        new OfferSnapshot.Group(200L, "Электрика", "Elektryka", List.of(wire))));
    }

    private static OfferSnapshot.Row row(Long workItemId, String name, String price, List<OfferSnapshot.Cell> cells) {
        return new OfferSnapshot.Row(workItemId, name, name, "m2", "m2", "m2", new BigDecimal("10"),
                new BigDecimal(price), new BigDecimal(price), new BigDecimal("100"), new BigDecimal("100"), cells);
    }

    private ToolContext ctx(String role) {
        return new ToolContext(role, PROJECT, 77L, 5L, snapshot(), new EditBuffer());
    }

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ------------------------------------------------------------ registry

    @Test
    void registryContainsExactlyTheAllowedTools() {
        assertThat(registry.toolNames()).containsExactlyElementsOf(OfferChatToolRegistry.TOOL_NAMES);
        assertThat(OfferChatToolRegistry.TOOL_NAMES).containsExactlyInAnyOrder("find_work", "get_offer_state",
                "assign_work", "unassign_work", "set_volume", "clear_volume_override", "adjust_price",
                "set_line_comment");
        List<String> declared = new ArrayList<>();
        registry.declarations().forEach(d -> declared.add(d.get("name").asText()));
        assertThat(declared).containsExactlyElementsOf(OfferChatToolRegistry.TOOL_NAMES);
    }

    @Test
    void everyWriteToolRequiresReasonAndWritePermission() {
        for (String name : registry.toolNames()) {
            OfferChatTool t = registry.tool(name);
            boolean write = t.permission().isWrite();
            assertThat(write).isEqualTo(!name.equals("find_work") && !name.equals("get_offer_state"));
            JsonNode required = t.params().get("required");
            boolean hasReason = false;
            if (required != null) {
                for (JsonNode r : required) {
                    hasReason |= "reason".equals(r.asText());
                }
            }
            assertThat(hasReason).as(name).isEqualTo(write);
        }
        assertThat(registry.tool("assign_work").permission().required())
                .extracting(ToolPermission.Required::resource, ToolPermission.Required::operation)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("ESTIMATE", "UPDATE"),
                        org.assertj.core.groups.Tuple.tuple("OFFERS", "UPDATE"));
        assertThat(registry.tool("find_work").permission().required())
                .extracting(ToolPermission.Required::resource, ToolPermission.Required::operation)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("OFFERS", "READ"));
    }

    @Test
    void callWithoutReasonIsAToolErrorForEveryWriteTool() {
        Map<String, String> calls = Map.of(
                "assign_work", "{\"workCode\":\"TILE\",\"roomIds\":[501]}",
                "unassign_work", "{\"workCode\":\"PAINT\",\"all\":true}",
                "set_volume", "{\"workCode\":\"PAINT\",\"roomId\":501,\"quantity\":5}",
                "clear_volume_override", "{\"workCode\":\"PAINT\",\"roomId\":501}",
                "adjust_price", "{\"scope\":\"PROJECT\",\"pct\":10}",
                "set_line_comment", "{\"workCode\":\"PAINT\",\"text\":\"x\"}");
        calls.forEach((name, args) -> {
            ToolContext c = ctx("EDITOR");
            ToolOutput out = registry.run(c, name, json(args));
            assertThat(out.ok()).as(name).isFalse();
            assertThat(out.error()).as(name).isEqualTo("error.estimateai.tool.reason_required");
            assertThat(c.buffer().isEmpty()).as(name).isTrue();
            // blank reason is the same as a missing one
            ToolOutput blank = registry.run(ctx("EDITOR"), name,
                    json(args.substring(0, args.length() - 1) + ",\"reason\":\"   \"}"));
            assertThat(blank.error()).as(name).isEqualTo("error.estimateai.tool.reason_required");
        });
    }

    @Test
    void permissionIsCheckedPerToolThroughTheEvaluator() {
        ToolOutput write = registry.run(ctx("VIEWER"), "set_volume",
                json("{\"workCode\":\"PAINT\",\"roomId\":501,\"quantity\":5,\"reason\":\"r\"}"));
        assertThat(write.error()).isEqualTo("error.estimateai.tool.forbidden");

        ToolOutput read = registry.run(ctx("VIEWER"), "find_work", json("{\"query\":\"til\"}"));
        assertThat(read.ok()).isTrue();

        ToolOutput nobody = registry.run(ctx("STRANGER"), "find_work", json("{\"query\":\"til\"}"));
        assertThat(nobody.error()).isEqualTo("error.estimateai.tool.forbidden");

        ToolOutput unknown = registry.run(ctx("EDITOR"), "delete_room", json("{}"));
        assertThat(unknown.error()).isEqualTo("error.estimateai.tool.unknown_tool");
    }

    @Test
    void executeReturnsValidationErrorsAsToolResults() {
        OfferChatClient.ToolResult r = registry.execute(ctx("EDITOR"),
                new OfferChatClient.ToolCall("set_volume",
                        json("{\"workCode\":\"NOPE\",\"roomId\":501,\"quantity\":5,\"reason\":\"r\"}")));
        assertThat(r.name()).isEqualTo("set_volume");
        assertThat(r.result().get("ok").asBoolean()).isFalse();
        assertThat(r.result().get("error").asText()).isEqualTo("error.estimateai.tool.unknown_work");
    }

    // --------------------------------------------------------------- tools

    @Test
    void findWorkReturnsCatalogHitsAndNeedsAQuery() {
        ToolOutput out = registry.run(ctx("EDITOR"), "find_work", json("{\"query\":\"плит\"}"));
        assertThat(out.ok()).isTrue();
        assertThat(out.data().get("works")).hasSize(1);
        assertThat(out.data().get("works").get(0).get("code").asText()).isEqualTo("TILE");
        assertThat(registry.run(ctx("EDITOR"), "find_work", json("{}")).ok()).isFalse();
    }

    @Test
    void assignWorkAllMatchingUsesRoomTypesAndWritesCellMeta() {
        ToolContext c = ctx("EDITOR");
        // TILE exists in room 501 already (snapshot) and is restricted to room type 1 => nothing new to assign
        ToolOutput out = registry.run(c, "assign_work",
                json("{\"workCode\":\"TILE\",\"allMatching\":true,\"reason\":\"tile all wet rooms\"}"));
        assertThat(out.ok()).isTrue();
        assertThat(out.data().get("assignedRoomIds")).isEmpty();
        assertThat(out.data().get("alreadyAssignedRoomIds")).hasSize(1);

        // WIRE is unrestricted: room 501 is new, 502 exists
        ToolOutput wire = registry.run(c, "assign_work",
                json("{\"workCode\":\"WIRE\",\"roomIds\":\"all_matching\",\"reason\":\"wiring everywhere\"}"));
        assertThat(wire.ok()).isTrue();
        assertThat(c.buffer().edits()).hasSize(1);
        StagedEdit e = c.buffer().edits().get(0);
        assertThat(e.kind()).isEqualTo(EditKind.ASSIGN);
        assertThat(e.workItemId()).isEqualTo(12L);
        assertThat(e.roomId()).isEqualTo(501L);
        CellMetaInput m = c.buffer().metaInputs().get(0);
        assertThat(m.workCode()).isEqualTo("WIRE");
        assertThat(m.origin()).isEqualTo("CHAT");
        assertThat(m.turnId()).isEqualTo(5L);
        assertThat(m.rationale()).isEqualTo("wiring everywhere");
        assertThat(c.buffer().touchedWorkIds()).containsExactly(12L);
    }

    @Test
    void assignWorkValidates() {
        String r = ",\"reason\":\"r\"}";
        assertThat(registry.run(ctx("EDITOR"), "assign_work", json("{\"workCode\":\"NOPE\",\"roomIds\":[501]" + r)).error())
                .isEqualTo("error.estimateai.tool.unknown_work");
        assertThat(registry.run(ctx("EDITOR"), "assign_work", json("{\"workCode\":\"OLD\",\"roomIds\":[501]" + r)).error())
                .isEqualTo("error.estimateai.tool.unknown_work");
        assertThat(registry.run(ctx("EDITOR"), "assign_work", json("{\"workCode\":\"FREE\",\"roomIds\":[501]" + r)).error())
                .isEqualTo("error.estimateai.tool.no_price");
        assertThat(registry.run(ctx("EDITOR"), "assign_work", json("{\"workCode\":\"WIRE\",\"roomIds\":[999]" + r)).error())
                .isEqualTo("error.estimateai.tool.unknown_room");
    }

    @Test
    void unassignAllRemovesEveryCellOfTheWork() {
        ToolContext c = ctx("EDITOR");
        ToolOutput out = registry.run(c, "unassign_work",
                json("{\"workCode\":\"PAINT\",\"all\":true,\"reason\":\"client declined painting\"}"));
        assertThat(out.ok()).isTrue();
        assertThat(c.buffer().edits()).extracting(StagedEdit::kind).containsOnly(EditKind.UNASSIGN);
        assertThat(c.buffer().edits()).extracting(StagedEdit::roomId).containsExactlyInAnyOrder(501L, 502L);
        // a second call sees the buffered removal
        assertThat(registry.run(c, "unassign_work", json("{\"workCode\":\"PAINT\",\"all\":true,\"reason\":\"r\"}")).error())
                .isEqualTo("error.estimateai.tool.work_not_in_offer");
        assertThat(registry.run(ctx("EDITOR"), "unassign_work",
                json("{\"workCode\":\"TILE\",\"roomIds\":[502],\"reason\":\"r\"}")).error())
                .isEqualTo("error.estimateai.tool.cell_not_found");
    }

    @Test
    void setVolumeAndClearOverrideBuildQuantityEditsAndMeta() {
        ToolContext c = ctx("EDITOR");
        ToolOutput set = registry.run(c, "set_volume",
                json("{\"workCode\":\"PAINT\",\"roomId\":501,\"quantity\":\"32,5\",\"reason\":\"ceiling is higher\"}"));
        assertThat(set.ok()).isTrue();
        StagedEdit e = c.buffer().edits().get(0);
        assertThat(e.kind()).isEqualTo(EditKind.SET_QUANTITY);
        assertThat(e.quantity()).isEqualByComparingTo("32.5");
        CellMetaInput m = c.buffer().metaInputs().get(0);
        assertThat(m.volumeOverridden()).isTrue();
        assertThat(m.volumeReason()).isEqualTo("ceiling is higher");

        ToolOutput clear = registry.run(c, "clear_volume_override",
                json("{\"workCode\":\"PAINT\",\"roomId\":501,\"reason\":\"back to formula\"}"));
        assertThat(clear.ok()).isTrue();
        assertThat(c.buffer().edits().get(1).kind()).isEqualTo(EditKind.CLEAR_QUANTITY);
        assertThat(c.buffer().metaInputs()).hasSize(1);
        assertThat(c.buffer().metaInputs().get(0).volumeOverridden()).isFalse();
        assertThat(c.buffer().metaInputs().get(0).rationale()).isEqualTo("back to formula");
    }

    @Test
    void setVolumeRejectsBadInput() {
        String r = ",\"reason\":\"r\"}";
        assertThat(registry.run(ctx("EDITOR"), "set_volume", json("{\"workCode\":\"PAINT\",\"roomId\":501,\"quantity\":-1" + r)).error())
                .isEqualTo("error.estimateai.tool.invalid_quantity");
        assertThat(registry.run(ctx("EDITOR"), "set_volume", json("{\"workCode\":\"PAINT\",\"roomId\":501,\"quantity\":0" + r)).error())
                .isEqualTo("error.estimateai.tool.invalid_quantity");
        assertThat(registry.run(ctx("EDITOR"), "set_volume", json("{\"workCode\":\"PAINT\",\"roomId\":999,\"quantity\":1" + r)).error())
                .isEqualTo("error.estimateai.tool.unknown_room");
        assertThat(registry.run(ctx("EDITOR"), "set_volume", json("{\"workCode\":\"TILE\",\"roomId\":502,\"quantity\":1" + r)).error())
                .isEqualTo("error.estimateai.tool.cell_not_found");
    }

    @Test
    void adjustPriceProjectScopeCarriesThePriceReasonOnEveryCellOfEveryLine() {
        ToolContext c = ctx("EDITOR");
        ToolOutput out = registry.run(c, "adjust_price",
                json("{\"scope\":\"PROJECT\",\"pct\":15,\"reason\":\"markup 15% requested\"}"));
        assertThat(out.ok()).isTrue();
        StagedEdit e = c.buffer().edits().get(0);
        assertThat(e.kind()).isEqualTo(EditKind.ADJUST_LABOUR_PRICE);
        assertThat(e.scope()).isEqualTo(LabourPriceAdjustmentScope.PROJECT);
        assertThat(e.targetId()).isNull();
        assertThat(e.pct()).isEqualByComparingTo("15");
        // TILE:501, PAINT:501, PAINT:502, WIRE:502
        assertThat(c.buffer().metaInputs()).hasSize(4)
                .allSatisfy(m -> {
                    assertThat(m.priceReason()).isEqualTo("markup 15% requested");
                    assertThat(m.rationale()).isNull();
                    assertThat(m.origin()).isEqualTo("CHAT");
                });
        assertThat(c.buffer().wide()).isTrue();
        assertThat(c.buffer().touchedWorkIds()).containsExactlyInAnyOrder(10L, 11L, 12L);
    }

    @Test
    void adjustPriceCategoryAndWorkScopesResolveTargetsAndLimitCells() {
        ToolContext c = ctx("EDITOR");
        assertThat(registry.run(c, "adjust_price",
                json("{\"scope\":\"WORK_CATEGORY\",\"targetCode\":\"ELE\",\"pct\":-10,\"reason\":\"discount\"}")).ok()).isTrue();
        assertThat(c.buffer().edits().get(0).targetId()).isEqualTo(200L);
        assertThat(c.buffer().metaInputs()).extracting(CellMetaInput::workCode).containsExactly("WIRE");
        assertThat(c.buffer().wide()).isFalse();

        ToolContext c2 = ctx("EDITOR");
        assertThat(registry.run(c2, "adjust_price",
                json("{\"scope\":\"WORK_ITEM\",\"targetCode\":\"PAINT\",\"pct\":5,\"reason\":\"r\"}")).ok()).isTrue();
        assertThat(c2.buffer().edits().get(0).targetId()).isEqualTo(11L);
        assertThat(c2.buffer().metaInputs()).hasSize(2);
    }

    @Test
    void adjustPriceValidates() {
        String r = ",\"reason\":\"r\"}";
        assertThat(registry.run(ctx("EDITOR"), "adjust_price", json("{\"scope\":\"PROJECT\",\"pct\":1001" + r)).error())
                .isEqualTo("error.estimateai.tool.invalid_pct");
        assertThat(registry.run(ctx("EDITOR"), "adjust_price", json("{\"scope\":\"PROJECT\",\"pct\":-100.5" + r)).error())
                .isEqualTo("error.estimateai.tool.invalid_pct");
        assertThat(registry.run(ctx("EDITOR"), "adjust_price", json("{\"scope\":\"PROJECT\",\"pct\":-100" + r)).ok()).isTrue();
        assertThat(registry.run(ctx("EDITOR"), "adjust_price", json("{\"scope\":\"GLOBAL\",\"pct\":1" + r)).error())
                .isEqualTo("error.estimateai.tool.invalid_scope");
        assertThat(registry.run(ctx("EDITOR"), "adjust_price",
                json("{\"scope\":\"WORK_CATEGORY\",\"targetCode\":\"NOPE\",\"pct\":1" + r)).error())
                .isEqualTo("error.estimateai.tool.unknown_target");
        assertThat(registry.run(ctx("EDITOR"), "adjust_price", json("{\"scope\":\"WORK_ITEM\",\"pct\":1" + r)).error())
                .isEqualTo("error.estimateai.tool.unknown_target");
    }

    @Test
    void setLineCommentBuffersTheCommentForLinesInTheOffer() {
        ToolContext c = ctx("EDITOR");
        assertThat(registry.run(c, "set_line_comment",
                json("{\"workCode\":\"PAINT\",\"text\":\"two coats\",\"reason\":\"asked\"}")).ok()).isTrue();
        assertThat(c.buffer().comments()).hasSize(1);
        assertThat(c.buffer().comments().get(0).text()).isEqualTo("two coats");
        assertThat(c.buffer().edits()).isEmpty();
        assertThat(registry.run(ctx("EDITOR"), "set_line_comment",
                json("{\"workCode\":\"FREE\",\"text\":\"x\",\"reason\":\"r\"}")).error())
                .isEqualTo("error.estimateai.tool.work_not_in_offer");
    }

    @Test
    void getOfferStateRendersTheSnapshotWithWorkCodes() {
        ToolOutput out = registry.run(ctx("VIEWER"), "get_offer_state", json("{}"));
        assertThat(out.ok()).isTrue();
        String state = out.data().get("state").asText();
        assertThat(state).contains("net=1000", "501=Bathroom", "TILE", "PAINT", "WIRE");
    }

    // ------------------------------------------------------------ renderer

    @Test
    void rendererFiltersAndIsBounded() {
        Map<Long, String> codes = Map.of(10L, "TILE", 11L, "PAINT", 12L, "WIRE");
        String filtered = SnapshotTextRenderer.render(snapshot(), codes, "wire", 0);
        assertThat(filtered).contains("WIRE").doesNotContain("TILE", "PAINT");

        String none = SnapshotTextRenderer.render(snapshot(), codes, "zzz", 0);
        assertThat(none).contains("no rows match");

        String tiny = SnapshotTextRenderer.render(snapshot(), codes, null, 190);
        assertThat(tiny.length()).isLessThanOrEqualTo(190 + 120);
        assertThat(tiny).contains("more rows not shown");

        String room = SnapshotTextRenderer.render(snapshot(), codes, "Kitchen", 0);
        assertThat(room).contains("PAINT", "WIRE").doesNotContain("TILE ");
    }

    @Test
    void rendererIncludesShortCellExplanationAndPriceBlock() {
        CellAiMeta ai = new CellAiMeta("JOB", null, "ASSUMED", new BigDecimal("0.7"), null,
                "Niche on the east wall is tiled, sheet 3 shows it fully",
                new CellAiMeta.Volume("MODEL_OVERRIDE", "wallArea", new BigDecimal("12.4"), new BigDecimal("14"),
                        new CellAiMeta.Correction(new BigDecimal("1.6"), new BigDecimal("12.9")), "niche"),
                null, new CellAiMeta.Price(new BigDecimal("50"), new BigDecimal("57.5"), new BigDecimal("15"),
                        "PROJECT", "markup stated"));
        OfferSnapshot.Row r = row(10L, "Плитка", "57.5", List.of(
                new OfferSnapshot.Cell(501L, new BigDecimal("14"), true, "wallArea", false, ai)));
        OfferSnapshot s = new OfferSnapshot(3, "t", BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE,
                List.of(new OfferSnapshot.Room(501L, "Bathroom")),
                List.of(new OfferSnapshot.Group(100L, "Отделка", "W", List.of(r))));
        String text = SnapshotTextRenderer.render(s, Map.of(10L, "TILE"), null, 0);
        assertThat(text).contains("{AI ASSUMED vol=MODEL_OVERRIDE f=12.4 \"Niche on the east wall",
                "price-ai: 15% PROJECT \"markup stated\"");
    }

    @Test
    void rendererShowsPriceOverrideAndComment() {
        OfferSnapshot.Row r = new OfferSnapshot.Row(10L, "Плитка", "Płytki", "m2", "m2", "m2",
                new BigDecimal("10"), new BigDecimal("57.5"), new BigDecimal("50"), new BigDecimal("575"),
                new BigDecimal("500"), List.of(new OfferSnapshot.Cell(501L, new BigDecimal("10"))),
                new BigDecimal("57.5"), "two layers", null, 1);
        OfferSnapshot s = new OfferSnapshot(3, "t", BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE,
                List.of(new OfferSnapshot.Room(501L, "Bathroom")),
                List.of(new OfferSnapshot.Group(100L, "Отделка", "W", List.of(r))));
        String text = SnapshotTextRenderer.render(s, Map.of(10L, "TILE"), null, 0);
        assertThat(text).contains("price=57.5", "base 50", "override 57.5", "comment: \"two layers\"");
    }
}
