package com.foremen.service.estimateai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;

/**
 * FOR-05-04 (Requirements 5.1, 7.3) — renders the active catalog for the planner in the fixed structure
 * {@code ### <category code> <category name>} / {@code #### <work code> <work name>}, with the section
 * rule once per category and the work rule next to the work. No prices are ever rendered.
 *
 * <p>The text is split into batches of at most {@code maxChars} characters on category boundaries. A
 * category that alone exceeds the bound is split by works, repeating its header and section rule in each
 * part. Pure: works on {@link CategoryView}s so it is testable without JPA.
 */
public final class CatalogContextBuilder {

    private CatalogContextBuilder() {
    }

    public record WorkView(String code, String namePl, String nameRu, String unit, List<String> roomTypeCodes,
                           String aiInstructions) {
        public WorkView {
            roomTypeCodes = roomTypeCodes == null ? List.of() : List.copyOf(roomTypeCodes);
        }
    }

    public record CategoryView(String code, String namePl, String nameRu, String aiInstructions,
                               List<WorkView> works) {
        public CategoryView {
            works = works == null ? List.of() : List.copyOf(works);
        }
    }

    /** One model call worth of catalog: its text and the work codes it lists, in order. */
    public record Batch(String text, List<String> workCodes) {
        public Batch {
            workCodes = List.copyOf(workCodes);
        }
    }

    /** Maps work items to views; inactive items and categories are dropped. Needs an open session (lazy room types). */
    public static List<CategoryView> fromEntities(Collection<WorkItemEntity> items) {
        Map<WorkCategoryEntity, List<WorkItemEntity>> byCategory = new LinkedHashMap<>();
        items.stream()
                .filter(w -> w.isActive() && w.getWorkCategory() != null && w.getWorkCategory().isActive())
                .sorted(Comparator
                        .comparing((WorkItemEntity w) -> w.getWorkCategory().getOrderNo(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(w -> w.getWorkCategory().getCode(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(WorkItemEntity::getCode, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(w -> byCategory.computeIfAbsent(w.getWorkCategory(), c -> new ArrayList<>()).add(w));
        List<CategoryView> out = new ArrayList<>();
        byCategory.forEach((c, ws) -> out.add(new CategoryView(c.getCode(), c.getNamePL(), c.getNameRU(),
                c.getAiInstructions(),
                ws.stream().map(w -> new WorkView(w.getCode(), w.getNamePL(), w.getNameRU(),
                        w.getUnit() == null ? null : w.getUnit().getCode(),
                        w.getRoomTypes().stream().map(RoomTypeEntity::getCode).sorted().toList(),
                        w.getAiInstructions())).toList())));
        return out;
    }

    public static List<Batch> build(List<CategoryView> categories, int maxChars) {
        List<Batch> batches = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<String> codes = new ArrayList<>();
        for (CategoryView c : categories == null ? List.<CategoryView>of() : categories) {
            if (c.works().isEmpty()) {
                continue;
            }
            for (Part part : parts(c, maxChars)) {
                if (text.length() > 0 && text.length() + part.text().length() > maxChars) {
                    batches.add(new Batch(text.toString(), codes));
                    text = new StringBuilder();
                    codes = new ArrayList<>();
                }
                text.append(part.text());
                codes.addAll(part.codes());
            }
        }
        if (text.length() > 0) {
            batches.add(new Batch(text.toString(), codes));
        }
        return batches;
    }

    private record Part(String text, List<String> codes) {
    }

    private static List<Part> parts(CategoryView c, int maxChars) {
        String header = header(c);
        List<Part> out = new ArrayList<>();
        StringBuilder text = new StringBuilder(header);
        List<String> codes = new ArrayList<>();
        for (WorkView w : c.works()) {
            String block = work(w);
            if (!codes.isEmpty() && text.length() + block.length() > maxChars) {
                out.add(new Part(text.toString(), codes));
                text = new StringBuilder(header);
                codes = new ArrayList<>();
            }
            text.append(block);
            codes.add(w.code());
        }
        out.add(new Part(text.toString(), codes));
        return out;
    }

    private static String header(CategoryView c) {
        StringBuilder sb = new StringBuilder("### ").append(nz(c.code())).append(' ')
                .append(names(c.namePl(), c.nameRu())).append('\n');
        if (hasText(c.aiInstructions())) {
            sb.append("Section rule: ").append(c.aiInstructions().strip()).append('\n');
        }
        return sb.toString();
    }

    private static String work(WorkView w) {
        StringBuilder sb = new StringBuilder("#### ").append(nz(w.code())).append(' ')
                .append(names(w.namePl(), w.nameRu()));
        sb.append(" | unit: ").append(w.unit() == null ? "-" : w.unit());
        sb.append(" | rooms: ").append(w.roomTypeCodes().isEmpty() ? "all" : String.join(", ", w.roomTypeCodes()));
        sb.append('\n');
        if (hasText(w.aiInstructions())) {
            sb.append("Work rule: ").append(w.aiInstructions().strip()).append('\n');
        }
        return sb.toString();
    }

    private static String names(String pl, String ru) {
        if (hasText(pl) && hasText(ru) && !pl.strip().equals(ru.strip())) {
            return pl.strip() + " / " + ru.strip();
        }
        return hasText(pl) ? pl.strip() : hasText(ru) ? ru.strip() : "";
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }
}
