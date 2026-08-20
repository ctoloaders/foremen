package com.foremen.controller.property;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for Pagination Structure Preservation.
 *
 * <p><b>Property 2: Pagination Structure Preservation</b></p>
 *
 * <p>For any Pageable (with arbitrary page number, size, and sort) and for any service result
 * {@code Page<ServiceModel>}, calling {@code page.map(mapper::toDto)} in the controller's find method
 * SHALL produce a {@code Page<DtoModel>} where:</p>
 * <ul>
 *   <li>totalElements equals the original page's totalElements</li>
 *   <li>number (page index) equals the original page's number</li>
 *   <li>size equals the original page's size</li>
 *   <li>content.size() equals the original page's content size</li>
 *   <li>Each element in content is the result of mapper.toDto(originalElement) at the same index</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 5.1, 5.2, 7.1, 7.2, 13.3</b></p>
 */
class PaginationPreservationPropertyTest {

    /**
     * Property 2: Pagination Structure Preservation
     *
     * Generate random Page objects and apply page.map() with an identity-like mapper.
     * Verify all page metadata and content is preserved correctly.
     *
     * Validates: Requirements 5.1, 5.2, 7.1, 7.2, 13.3
     */
    @Property(tries = 100)
    @Tag("Feature: FOR-01-07-crud-controller, Property 2: Pagination Structure Preservation")
    void paginationMetadataPreservedAfterMap(
            @ForAll("pageParameters") PageParameters params) {

        // Arrange: build a Page<String> with random metadata
        List<String> content = IntStream.range(0, params.contentSize())
                .mapToObj(i -> "item-" + i + "-" + params.pageNumber())
                .toList();

        Pageable pageable = PageRequest.of(params.pageNumber(), params.size());
        Page<String> originalPage = new PageImpl<>(content, pageable, params.totalElements());

        // Act: map using an identity-like function (simulates mapper::toDto)
        Function<String, String> identityMapper = s -> "mapped:" + s;
        Page<String> mappedPage = originalPage.map(identityMapper);

        // Assert: all pagination metadata preserved
        assertThat(mappedPage.getTotalElements())
                .as("totalElements must be preserved")
                .isEqualTo(originalPage.getTotalElements());

        assertThat(mappedPage.getNumber())
                .as("page number must be preserved")
                .isEqualTo(originalPage.getNumber());

        assertThat(mappedPage.getSize())
                .as("page size must be preserved")
                .isEqualTo(originalPage.getSize());

        assertThat(mappedPage.getContent().size())
                .as("content size must be preserved")
                .isEqualTo(originalPage.getContent().size());

        // Assert: each element is mapped at the same index
        for (int i = 0; i < originalPage.getContent().size(); i++) {
            String original = originalPage.getContent().get(i);
            String mapped = mappedPage.getContent().get(i);
            assertThat(mapped)
                    .as("element at index %d must be the mapped result", i)
                    .isEqualTo(identityMapper.apply(original));
        }
    }

    /**
     * Property 2 (variant): Empty page preserves metadata.
     *
     * When totalElements is 0 and content is empty, page.map() still preserves
     * all metadata correctly.
     *
     * Validates: Requirements 5.1, 5.2, 7.1, 7.2, 13.3
     */
    @Property(tries = 100)
    @Tag("Feature: FOR-01-07-crud-controller, Property 2: Pagination Structure Preservation")
    void emptyPagePreservesMetadataAfterMap(
            @ForAll @IntRange(min = 0, max = 50) int pageNumber,
            @ForAll @IntRange(min = 1, max = 100) int size) {

        // Arrange: empty page
        Pageable pageable = PageRequest.of(pageNumber, size);
        Page<Integer> emptyPage = new PageImpl<>(List.of(), pageable, 0);

        // Act
        Page<String> mappedPage = emptyPage.map(Object::toString);

        // Assert
        assertThat(mappedPage.getTotalElements()).isEqualTo(0);
        assertThat(mappedPage.getNumber()).isEqualTo(pageNumber);
        assertThat(mappedPage.getSize()).isEqualTo(size);
        assertThat(mappedPage.getContent()).isEmpty();
    }

    /**
     * Property 2 (variant): Mapping function is applied to each element in order.
     *
     * Given a non-trivial mapping function, each element of the result page is the
     * function applied to the corresponding element of the source page.
     *
     * Validates: Requirements 5.1, 5.2, 7.1, 7.2, 13.3
     */
    @Property(tries = 100)
    @Tag("Feature: FOR-01-07-crud-controller, Property 2: Pagination Structure Preservation")
    void mappingFunctionAppliedToEachElementInOrder(
            @ForAll("pageParameters") PageParameters params) {

        // Arrange: create content with distinct integer values
        List<Integer> content = IntStream.range(0, params.contentSize())
                .boxed()
                .toList();

        Pageable pageable = PageRequest.of(params.pageNumber(), params.size());
        Page<Integer> originalPage = new PageImpl<>(content, pageable, params.totalElements());

        // Act: apply a doubling function
        Function<Integer, Integer> doubler = x -> x * 2;
        Page<Integer> mappedPage = originalPage.map(doubler);

        // Assert: each element is doubled, in the same order
        List<Integer> expectedContent = content.stream()
                .map(doubler)
                .toList();

        assertThat(mappedPage.getContent())
                .as("mapped content must equal element-wise application of the mapping function")
                .isEqualTo(expectedContent);
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<PageParameters> pageParameters() {
        Arbitrary<Long> totalElements = Arbitraries.longs().between(0, 1000);
        Arbitrary<Integer> pageNumber = Arbitraries.integers().between(0, 50);
        Arbitrary<Integer> size = Arbitraries.integers().between(1, 100);

        return Combinators.combine(totalElements, pageNumber, size)
                .as((total, page, sz) -> {
                    // Content size must be <= size and <= totalElements - (page * size)
                    // but for simplicity, we just ensure contentSize <= size
                    long remainingElements = Math.max(0, total - ((long) page * sz));
                    int contentSize = (int) Math.min(sz, remainingElements);
                    return new PageParameters(total, page, sz, contentSize);
                });
    }

    /**
     * Record holding the random parameters for a Page object.
     */
    record PageParameters(long totalElements, int pageNumber, int size, int contentSize) {}
}
