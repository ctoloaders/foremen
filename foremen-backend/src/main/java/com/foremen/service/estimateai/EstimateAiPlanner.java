package com.foremen.service.estimateai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.config.estimateai.EstimateAiProperties;
import com.foremen.service.estimateai.CatalogContextBuilder.Batch;
import com.foremen.service.estimateai.CatalogContextBuilder.CategoryView;
import com.foremen.service.estimateai.PlanCatalog.CatalogWork;
import com.foremen.service.estimateai.PlanCatalog.RoomFacts;
import com.foremen.service.estimateai.ai.EstimateAiException;
import com.foremen.service.estimateai.ai.EstimateAiException.Reason;
import com.foremen.service.estimateai.ai.EstimateAiPrompts;
import com.foremen.service.estimateai.ai.EstimatePlanClient;
import com.foremen.service.estimateai.ai.EstimatePlanClient.PlanRequest;

/**
 * FOR-05-04 (design B5; Requirements 5.1, 5.2, 5.6, 7.3, 7.4, 21.6) — the PLANNING_WORKS stage.
 *
 * <ol>
 *   <li>renders the catalog (no prices) in batches bounded by {@code max-catalog-chars}, one model call per
 *       batch, and merges the answers;
 *   <li>an invalid structured answer is retried once; the second one fails with
 *       {@link Reason#PLAN_INVALID} ({@code error.estimateai.failed.plan_invalid});
 *   <li>resolves the merged answer with {@link PlanResolver}; cells without a {@code rationale} are asked
 *       for once more, the rest keep {@code null} (the validator reports {@code RATIONALE_MISSING});
 *   <li>the user's prompt is passed as a separate delimited block.
 * </ol>
 */
@Service
public class EstimateAiPlanner {

    public static final String USER_INSTRUCTION_OPEN = "<user_instruction>";
    public static final String USER_INSTRUCTION_CLOSE = "</user_instruction>";

    private static final List<String> FIELDS = List.of("works", "assumptions", "questions", "unpriced",
            "coefficients");

    private final EstimatePlanClient client;
    private final EstimateAiProperties properties;

    public EstimateAiPlanner(EstimatePlanClient client, EstimateAiProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * @param plan          the validated plan
     * @param coefficients  raw {@code coefficients[]} of the model (stated by the user only), merged over batches
     * @param promptVersion version of the system prompt, recorded on the job
     */
    public record PlanResult(EstimatePlan plan, ArrayNode coefficients, String promptVersion) {
    }

    public PlanResult plan(String userPrompt, List<CategoryView> categories, String roomsText,
                           List<RoomFacts> rooms, Map<String, CatalogWork> catalog) {
        return plan(userPrompt, categories, roomsText, rooms, catalog, List.of());
    }

    /** Same as above; the job's input files are re-attached to every planning call (Requirement 5.1). */
    public PlanResult plan(String userPrompt, List<CategoryView> categories, String roomsText,
                           List<RoomFacts> rooms, Map<String, CatalogWork> catalog,
                           List<EstimatePlanClient.Attachment> attachments) {
        if (!client.isEnabled()) {
            throw new EstimateAiException(Reason.DISABLED, "Estimate AI is disabled");
        }
        EstimateAiPrompts.Prompt system = EstimateAiPrompts.system();
        String userBlock = delimitUserInstruction(userPrompt);
        List<Long> roomIds = (rooms == null ? List.<RoomFacts>of() : rooms).stream().map(RoomFacts::id).toList();
        List<Batch> batches = CatalogContextBuilder.build(categories, properties.maxCatalogChars());

        ObjectNode merged = JsonNodeFactory.instance.objectNode();
        for (String f : FIELDS) {
            merged.putArray(f);
        }
        for (Batch b : batches) {
            JsonNode raw = callValid(new PlanRequest(system.text(), userBlock, b.text(), roomsText, roomIds,
                    b.workCodes(), null, attachments));
            for (String f : FIELDS) {
                if (raw.get(f) instanceof ArrayNode a) {
                    ((ArrayNode) merged.get(f)).addAll(a);
                }
            }
        }

        EstimatePlan plan = PlanResolver.resolve(merged, rooms, catalog);
        List<PlannedWork> gaps = plan.works().stream().filter(w -> w.rationale() == null).toList();
        if (!gaps.isEmpty()) {
            reask(system.text(), userBlock, roomsText, roomIds, batches, gaps, merged, attachments);
            plan = PlanResolver.resolve(merged, rooms, catalog);
        }
        return new PlanResult(plan, (ArrayNode) merged.get("coefficients"), system.version());
    }

    /** Wraps the user's prompt in a delimited block; a tag inside the text cannot open or close the block. */
    public static String delimitUserInstruction(String prompt) {
        String text = prompt == null ? "" : prompt.strip();
        text = text.replace(USER_INSTRUCTION_CLOSE, "<\\/user_instruction>")
                .replace(USER_INSTRUCTION_OPEN, "<\\user_instruction>");
        return USER_INSTRUCTION_OPEN + "\n" + text + "\n" + USER_INSTRUCTION_CLOSE;
    }

    /** One call with a single retry on an invalid structured answer. */
    private JsonNode callValid(PlanRequest request) {
        for (int attempt = 1; ; attempt++) {
            try {
                JsonNode raw = client.plan(request).plan();
                if (raw != null && raw.isObject() && raw.get("works") != null && raw.get("works").isArray()) {
                    return raw;
                }
                throw new EstimateAiException(Reason.INVALID_RESPONSE, "Plan answer has no works array");
            } catch (EstimateAiException e) {
                if (e.reason() != Reason.INVALID_RESPONSE) {
                    throw e;
                }
                if (attempt >= 2) {
                    throw new EstimateAiException(Reason.PLAN_INVALID, "Plan answer invalid twice", e);
                }
            }
        }
    }

    /** Asks once for the missing rationales and patches them into {@code merged}; an invalid answer is ignored. */
    private void reask(String system, String userBlock, String roomsText, List<Long> roomIds,
                       List<Batch> batches, List<PlannedWork> gaps, ObjectNode merged,
                       List<EstimatePlanClient.Attachment> attachments) {
        Map<Integer, List<PlannedWork>> byBatch = new LinkedHashMap<>();
        for (PlannedWork g : gaps) {
            for (int i = 0; i < batches.size(); i++) {
                if (batches.get(i).workCodes().contains(g.workCode())) {
                    byBatch.computeIfAbsent(i, k -> new ArrayList<>()).add(g);
                    break;
                }
            }
        }
        for (Map.Entry<Integer, List<PlannedWork>> e : byBatch.entrySet()) {
            Batch b = batches.get(e.getKey());
            StringBuilder ask = new StringBuilder("The previous answer has no rationale for these cells. "
                    + "Answer with the same schema, only for these (workCode, roomId) pairs, each with a "
                    + "non-empty rationale:\n");
            Set<String> wanted = new LinkedHashSet<>();
            for (PlannedWork g : e.getValue()) {
                if (wanted.add(g.workCode() + "|" + g.roomId())) {
                    ask.append("- ").append(g.workCode()).append(" in room ").append(g.roomId()).append('\n');
                }
            }
            JsonNode answer;
            try {
                answer = client.plan(new PlanRequest(system, userBlock, b.text(), roomsText, roomIds,
                        b.workCodes(), ask.toString(), attachments)).plan();
            } catch (EstimateAiException ex) {
                if (ex.reason() == Reason.INVALID_RESPONSE) {
                    continue;
                }
                throw ex;
            }
            patchRationales((ArrayNode) merged.get("works"), answer, wanted);
        }
    }

    private static void patchRationales(ArrayNode works, JsonNode answer, Set<String> wanted) {
        if (answer == null || !(answer.get("works") instanceof ArrayNode answered)) {
            return;
        }
        for (JsonNode w : answered) {
            String code = textOrNull(w.get("workCode"));
            if (code == null) {
                continue;
            }
            String workLevel = textOrNull(w.get("rationale"));
            Map<Long, String> perRoom = new LinkedHashMap<>();
            if (w.get("volumes") instanceof ArrayNode vs) {
                for (JsonNode v : vs) {
                    String r = textOrNull(v.get("rationale"));
                    if (r != null && v.path("roomId").canConvertToLong()) {
                        perRoom.put(v.get("roomId").asLong(), r);
                    }
                }
            }
            for (String key : wanted) {
                int bar = key.lastIndexOf('|');
                if (!key.substring(0, bar).equals(code)) {
                    continue;
                }
                long roomId = Long.parseLong(key.substring(bar + 1));
                String r = perRoom.getOrDefault(roomId, workLevel);
                if (r != null) {
                    setRationale(works, code, roomId, r);
                }
            }
        }
    }

    private static void setRationale(ArrayNode works, String code, long roomId, String rationale) {
        for (JsonNode w : works) {
            if (!code.equals(textOrNull(w.get("workCode"))) || !(w instanceof ObjectNode wo)) {
                continue;
            }
            ArrayNode vs = wo.get("volumes") instanceof ArrayNode a ? a : wo.putArray("volumes");
            boolean set = false;
            for (JsonNode v : vs) {
                if (v instanceof ObjectNode vo && vo.path("roomId").asLong(-1) == roomId) {
                    vo.put("rationale", rationale);
                    set = true;
                }
            }
            if (!set) {
                vs.addObject().put("roomId", roomId).put("rationale", rationale);
            }
        }
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isNull() || !n.isValueNode()) {
            return null;
        }
        String t = n.asText().trim();
        return t.isEmpty() ? null : t;
    }
}
