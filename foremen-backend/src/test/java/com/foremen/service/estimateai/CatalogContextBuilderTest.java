package com.foremen.service.estimateai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.service.estimateai.CatalogContextBuilder.Batch;
import com.foremen.service.estimateai.CatalogContextBuilder.CategoryView;
import com.foremen.service.estimateai.CatalogContextBuilder.WorkView;

/** FOR-05-04 task 3.5 — structure (Requirement 7.3) and batching (Requirement 5.1) of the catalog context. */
class CatalogContextBuilderTest {

    private static WorkView work(String code, String rule) {
        return new WorkView(code, "Praca " + code, "Работа " + code, "m2", List.of("BATH"), rule);
    }

    private static CategoryView category(String code, String rule, int works) {
        List<WorkView> ws = new ArrayList<>();
        for (int i = 1; i <= works; i++) {
            ws.add(work(code + ".0" + i, i == 1 ? "work rule " + code : null));
        }
        return new CategoryView(code, "Kat " + code, "Кат " + code, rule, ws);
    }

    @Test
    void rendersFixedStructureWithRulesAndNoPrices() {
        List<Batch> batches = CatalogContextBuilder.build(List.of(category("7", "section rule seven", 2)), 100_000);

        assertThat(batches).hasSize(1);
        String text = batches.get(0).text();
        assertThat(text).startsWith("### 7 Kat 7 / Кат 7\nSection rule: section rule seven\n");
        assertThat(text).contains("#### 7.01 Praca 7.01 / Работа 7.01 | unit: m2 | rooms: BATH\nWork rule: work rule 7\n");
        assertThat(text).contains("#### 7.02 ");
        assertThat(text.indexOf("Section rule")).isEqualTo(text.lastIndexOf("Section rule"));
        assertThat(text.toLowerCase()).doesNotContain("price").doesNotContain("pln").doesNotContain("cena");
        assertThat(batches.get(0).workCodes()).containsExactly("7.01", "7.02");
    }

    @Test
    void splitsOnCategoryBoundariesWhenOverTheBound() {
        int one = CatalogContextBuilder.build(List.of(category("1", null, 2)), 100_000).get(0).text().length();
        List<CategoryView> cats = List.of(category("1", null, 2), category("2", null, 2), category("3", null, 2));

        List<Batch> batches = CatalogContextBuilder.build(cats, one + 5);

        assertThat(batches).hasSize(3);
        assertThat(batches).allSatisfy(b -> assertThat(b.text().length()).isLessThanOrEqualTo(one + 5));
        assertThat(batches.stream().flatMap(b -> b.workCodes().stream()).toList())
                .containsExactly("1.01", "1.02", "2.01", "2.02", "3.01", "3.02");
    }

    @Test
    void packsSmallCategoriesIntoOneBatch() {
        List<CategoryView> cats = List.of(category("1", null, 1), category("2", null, 1));
        assertThat(CatalogContextBuilder.build(cats, 100_000)).hasSize(1);
    }

    @Test
    void splitsOversizedCategoryByWorksRepeatingHeader() {
        CategoryView big = category("9", "rule nine", 4);
        int headerAndTwo = CatalogContextBuilder.build(List.of(category("9", "rule nine", 2)), 100_000)
                .get(0).text().length();

        List<Batch> batches = CatalogContextBuilder.build(List.of(big), headerAndTwo + 10);

        assertThat(batches).hasSizeGreaterThan(1);
        assertThat(batches).allSatisfy(b -> assertThat(b.text()).startsWith("### 9 Kat 9 / Кат 9\nSection rule: rule nine\n"));
        assertThat(batches.stream().flatMap(b -> b.workCodes().stream()).toList())
                .containsExactly("9.01", "9.02", "9.03", "9.04");
    }

    @Test
    void emptyCatalogYieldsNoBatches() {
        assertThat(CatalogContextBuilder.build(List.of(), 1000)).isEmpty();
        assertThat(CatalogContextBuilder.build(List.of(new CategoryView("1", "a", "b", "r", List.of())), 1000))
                .isEmpty();
    }

    @Test
    void roomContextListsIdLabelTypeAndDimensionsWithoutNulls() {
        RoomEntity r = new RoomEntity();
        r.setId(42L);
        r.setLabel(" Bathroom ");
        RoomTypeEntity type = new RoomTypeEntity();
        type.setCode("BATH");
        r.setRoomType(type);
        r.setFloorArea(new java.math.BigDecimal("4.500"));
        r.setDoorCount(1);

        String text = RoomContextBuilder.build(List.of(r));

        assertThat(text).isEqualTo("- room id=42; label=Bathroom; type=BATH; floorArea=4.5; doorCount=1\n");
    }
}
