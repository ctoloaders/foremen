package com.foremen.service.integration;

import com.foremen.service.integration.dao.SampleEntityDao;
import com.foremen.service.integration.entity.SampleEntity;
import com.foremen.service.integration.entity.SampleServiceExtendedModel;
import com.foremen.service.integration.entity.SampleServiceModel;
import com.foremen.service.integration.service.SampleReadOnlyService;
import com.foremen.service.integration.service.SampleSoftDeleteService;
import com.foremen.exception.ForemenApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("integration")
@DisplayName("ReadOnlyAdminService Integration Tests")
class ReadOnlyAdminServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("integration/init-sample-entity.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private SampleReadOnlyService readOnlyService;

    @Autowired
    private SampleSoftDeleteService softDeleteService;

    @Autowired
    private SampleEntityDao sampleEntityDao;

    @BeforeEach
    void setUp() {
        sampleEntityDao.deleteAll();

        SampleEntity entity1 = new SampleEntity();
        entity1.setNameRU("Тест");
        entity1.setNamePL("Test");
        entity1.setStatus("ACTIVE");
        entity1.setAge(25);
        entity1.setDeleted(false);

        SampleEntity entity2 = new SampleEntity();
        entity2.setNameRU("Пример");
        entity2.setNamePL("Przyklad");
        entity2.setStatus("ACTIVE");
        entity2.setAge(30);
        entity2.setDeleted(false);

        SampleEntity entity3 = new SampleEntity();
        entity3.setNameRU("Удалённый");
        entity3.setNamePL("Usuniety");
        entity3.setStatus("ACTIVE");
        entity3.setAge(35);
        entity3.setDeleted(true);

        SampleEntity entity4 = new SampleEntity();
        entity4.setNameRU("Заблокированный");
        entity4.setNamePL("Zablokowany");
        entity4.setStatus("BANNED");
        entity4.setAge(40);
        entity4.setDeleted(false);

        SampleEntity entity5 = new SampleEntity();
        entity5.setNameRU("Альфа");
        entity5.setNamePL("Alfa");
        entity5.setStatus("ACTIVE");
        entity5.setAge(20);
        entity5.setDeleted(false);

        sampleEntityDao.saveAll(List.of(entity1, entity2, entity3, entity4, entity5));
    }

    @AfterEach
    void tearDown() {
        sampleEntityDao.deleteAll();
        LocaleContextHolder.resetLocaleContext();
    }

    @Nested
    @DisplayName("find with query filter")
    class FindWithQueryFilter {

        @Test
        @DisplayName("should filter by exact field value")
        void shouldFilterByExactFieldValue() {
            Pageable pageable = PageRequest.of(0, 10);

            Page<SampleServiceModel> result = readOnlyService.find(pageable, "status==ACTIVE");

            assertThat(result.getContent()).hasSize(4);
            assertThat(result.getContent())
                    .allMatch(m -> "ACTIVE".equals(m.getStatus()));
        }

        @Test
        @DisplayName("should filter by age comparison")
        void shouldFilterByAgeComparison() {
            Pageable pageable = PageRequest.of(0, 10);

            Page<SampleServiceModel> result = readOnlyService.find(pageable, "age>28");

            assertThat(result.getContent()).hasSize(3);
            assertThat(result.getContent())
                    .allMatch(m -> m.getAge() > 28);
        }

        @Test
        @DisplayName("should filter by combined AND query")
        void shouldFilterByCombinedAndQuery() {
            Pageable pageable = PageRequest.of(0, 10);

            Page<SampleServiceModel> result = readOnlyService.find(pageable, "status==ACTIVE AND age>25");

            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getContent())
                    .allMatch(m -> "ACTIVE".equals(m.getStatus()) && m.getAge() > 25);
        }

        @Test
        @DisplayName("should return empty page when no matches")
        void shouldReturnEmptyPageWhenNoMatches() {
            Pageable pageable = PageRequest.of(0, 10);

            Page<SampleServiceModel> result = readOnlyService.find(pageable, "status==NONEXISTENT");

            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
        }
    }

    @Nested
    @DisplayName("find with i18n filter")
    class FindWithI18nFilter {

        @Test
        @DisplayName("should query nameRU when locale is Russian")
        void shouldQueryNameRuWhenLocaleIsRussian() {
            LocaleContextHolder.setLocale(Locale.of("ru"));
            Pageable pageable = PageRequest.of(0, 10);

            Page<SampleServiceModel> result = readOnlyService.find(pageable, "name==Тест");

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().getFirst().getName()).isEqualTo("Тест");
        }

        @Test
        @DisplayName("should query namePL when locale is Polish")
        void shouldQueryNamePlWhenLocaleIsPolish() {
            LocaleContextHolder.setLocale(Locale.of("pl"));
            Pageable pageable = PageRequest.of(0, 10);

            Page<SampleServiceModel> result = readOnlyService.find(pageable, "name==Test");

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().getFirst().getName()).isEqualTo("Test");
        }

        @Test
        @DisplayName("should query namePL for non-Russian locale")
        void shouldQueryNamePlForNonRussianLocale() {
            LocaleContextHolder.setLocale(Locale.ENGLISH);
            Pageable pageable = PageRequest.of(0, 10);

            Page<SampleServiceModel> result = readOnlyService.find(pageable, "name==Test");

            assertThat(result.getContent()).hasSize(1);
            // non-Russian defaults to PL suffix
            assertThat(result.getContent().getFirst().getName()).isEqualTo("Test");
        }
    }

    @Nested
    @DisplayName("find with paginated sort")
    class FindWithPaginatedSort {

        @Test
        @DisplayName("should sort by nameRU when locale is Russian")
        void shouldSortByNameRuWhenLocaleIsRussian() {
            LocaleContextHolder.setLocale(Locale.of("ru"));
            Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "name"));

            Page<SampleServiceModel> result = readOnlyService.find(pageable);

            List<String> names = result.getContent().stream()
                    .map(SampleServiceModel::getName)
                    .toList();
            // Russian sort by nameRU: Альфа, Заблокированный, Пример, Тест, Удалённый
            assertThat(names).isSorted();
        }

        @Test
        @DisplayName("should sort by namePL when locale is Polish")
        void shouldSortByNamePlWhenLocaleIsPolish() {
            LocaleContextHolder.setLocale(Locale.of("pl"));
            Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "name"));

            Page<SampleServiceModel> result = readOnlyService.find(pageable);

            List<String> names = result.getContent().stream()
                    .map(SampleServiceModel::getName)
                    .toList();
            // Polish sort by namePL: Alfa, Przyklad, Test, Usuniety, Zablokowany
            assertThat(names).isSorted();
        }

        @Test
        @DisplayName("should preserve descending sort direction")
        void shouldPreserveDescendingSortDirection() {
            LocaleContextHolder.setLocale(Locale.of("ru"));
            Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "name"));

            Page<SampleServiceModel> result = readOnlyService.find(pageable);

            List<String> names = result.getContent().stream()
                    .map(SampleServiceModel::getName)
                    .toList();
            assertThat(names).isSortedAccordingTo((a, b) -> b.compareTo(a));
        }

        @Test
        @DisplayName("should sort by non-i18n field unchanged")
        void shouldSortByNonI18nFieldUnchanged() {
            Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "age"));

            Page<SampleServiceModel> result = readOnlyService.find(pageable);

            List<Integer> ages = result.getContent().stream()
                    .map(SampleServiceModel::getAge)
                    .toList();
            assertThat(ages).isSorted();
        }
    }

    @Nested
    @DisplayName("findById and findByIdLocalized")
    class FindByIdOperations {

        @Test
        @DisplayName("findById should return extended model with all locale fields")
        void findByIdShouldReturnExtendedModel() {
            SampleEntity entity = sampleEntityDao.findAll(Pageable.unpaged()).getContent().stream()
                    .filter(e -> "Тест".equals(e.getNameRU()))
                    .findFirst().orElseThrow();

            SampleServiceExtendedModel result = readOnlyService.findById(entity.getId());

            assertThat(result.getId()).isEqualTo(entity.getId());
            assertThat(result.getNameRU()).isEqualTo("Тест");
            assertThat(result.getNamePL()).isEqualTo("Test");
            assertThat(result.getStatus()).isEqualTo("ACTIVE");
            assertThat(result.getAge()).isEqualTo(25);
        }

        @Test
        @DisplayName("findByIdLocalized should return locale-resolved model for RU")
        void findByIdLocalizedShouldReturnLocaleResolvedModelRU() {
            LocaleContextHolder.setLocale(Locale.of("ru"));
            SampleEntity entity = sampleEntityDao.findAll(Pageable.unpaged()).getContent().stream()
                    .filter(e -> "Тест".equals(e.getNameRU()))
                    .findFirst().orElseThrow();

            SampleServiceModel result = readOnlyService.findByIdLocalized(entity.getId());

            assertThat(result.getId()).isEqualTo(entity.getId());
            assertThat(result.getName()).isEqualTo("Тест");
            assertThat(result.getStatus()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("findByIdLocalized should return locale-resolved model for PL")
        void findByIdLocalizedShouldReturnLocaleResolvedModelPL() {
            LocaleContextHolder.setLocale(Locale.of("pl"));
            SampleEntity entity = sampleEntityDao.findAll(Pageable.unpaged()).getContent().stream()
                    .filter(e -> "Тест".equals(e.getNameRU()))
                    .findFirst().orElseThrow();

            SampleServiceModel result = readOnlyService.findByIdLocalized(entity.getId());

            assertThat(result.getId()).isEqualTo(entity.getId());
            assertThat(result.getName()).isEqualTo("Test");
        }

        @Test
        @DisplayName("findById should throw 404 when entity not found")
        void findByIdShouldThrow404WhenNotFound() {
            assertThatThrownBy(() -> readOnlyService.findById(99999L))
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> {
                        ForemenApiException apiEx = (ForemenApiException) ex;
                        assertThat(apiEx.getStatus().value()).isEqualTo(404);
                        assertThat(apiEx.getMessageCode()).isEqualTo("error.entity.not.found");
                    });
        }

        @Test
        @DisplayName("findByIdLocalized should throw 404 when entity not found")
        void findByIdLocalizedShouldThrow404WhenNotFound() {
            assertThatThrownBy(() -> readOnlyService.findByIdLocalized(99999L))
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> {
                        ForemenApiException apiEx = (ForemenApiException) ex;
                        assertThat(apiEx.getStatus().value()).isEqualTo(404);
                    });
        }
    }

    @Nested
    @DisplayName("findAllByIds batch operations")
    class FindAllByIdsBatch {

        @Test
        @DisplayName("should return results for existing IDs only")
        void shouldReturnResultsForExistingIdsOnly() {
            List<SampleEntity> allEntities = sampleEntityDao.findAll(Pageable.unpaged()).getContent();
            Long existingId1 = allEntities.get(0).getId();
            Long existingId2 = allEntities.get(1).getId();
            Long nonExistentId = 99999L;

            List<SampleServiceExtendedModel> result = readOnlyService.findAllByIds(
                    List.of(existingId1, existingId2, nonExistentId));

            assertThat(result).hasSize(2);
            assertThat(result).extracting(SampleServiceExtendedModel::getId)
                    .containsExactlyInAnyOrder(existingId1, existingId2);
        }

        @Test
        @DisplayName("should return empty list when no IDs match")
        void shouldReturnEmptyListWhenNoIdsMatch() {
            List<SampleServiceExtendedModel> result = readOnlyService.findAllByIds(
                    List.of(99998L, 99999L));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return all requested entities when all IDs exist")
        void shouldReturnAllRequestedEntities() {
            List<SampleEntity> allEntities = sampleEntityDao.findAll(Pageable.unpaged()).getContent();
            List<Long> allIds = allEntities.stream().map(SampleEntity::getId).toList();

            List<SampleServiceExtendedModel> result = readOnlyService.findAllByIds(allIds);

            assertThat(result).hasSize(allEntities.size());
        }
    }

    @Nested
    @DisplayName("isDeleted filtering")
    class IsDeletedFiltering {

        @Test
        @DisplayName("should exclude deleted entities from find results")
        void shouldExcludeDeletedEntitiesFromFind() {
            Pageable pageable = PageRequest.of(0, 10);

            Page<SampleServiceModel> result = softDeleteService.find(pageable);

            // Entity3 is deleted=true, should be excluded from softDeleteService
            // Entity4 is BANNED, excluded by addRequiredQuery
            // So only entity1, entity2, entity5 remain
            List<SampleServiceModel> content = result.getContent().stream()
                    .filter(java.util.Objects::nonNull)
                    .toList();
            assertThat(content).hasSize(3);
            assertThat(content).noneMatch(m -> "Удалённый".equals(m.getName()) || "Usuniety".equals(m.getName()));
        }

        @Test
        @DisplayName("should exclude deleted entities from findAllByIds")
        void shouldExcludeDeletedEntitiesFromFindAllByIds() {
            List<SampleEntity> allEntities = sampleEntityDao.findAll(Pageable.unpaged()).getContent();
            SampleEntity deletedEntity = allEntities.stream()
                    .filter(e -> Boolean.TRUE.equals(e.getDeleted()))
                    .findFirst().orElseThrow();

            List<SampleServiceExtendedModel> result = softDeleteService.findAllByIds(
                    List.of(deletedEntity.getId()));

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("addRequiredQuery (access criteria)")
    class AddRequiredQuery {

        @Test
        @DisplayName("should always apply access criteria with AND")
        void shouldAlwaysApplyAccessCriteriaWithAnd() {
            Pageable pageable = PageRequest.of(0, 10);

            // softDeleteService has addRequiredQuery = status != BANNED
            Page<SampleServiceModel> result = softDeleteService.find(pageable);

            List<SampleServiceModel> content = result.getContent().stream()
                    .filter(java.util.Objects::nonNull)
                    .toList();
            // BANNED entity should be excluded by access criteria
            assertThat(content).noneMatch(m -> "BANNED".equals(m.getStatus()));
        }

        @Test
        @DisplayName("access criteria should apply even when user query contains OR")
        void accessCriteriaShouldApplyEvenWithOrQuery() {
            LocaleContextHolder.setLocale(Locale.of("ru"));
            Pageable pageable = PageRequest.of(0, 10);

            // This query would match the BANNED entity via OR, but access criteria should exclude it
            Page<SampleServiceModel> result = softDeleteService.find(pageable, "age>35 OR status==ACTIVE");

            List<SampleServiceModel> content = result.getContent().stream()
                    .filter(java.util.Objects::nonNull)
                    .toList();
            // The BANNED entity (age=40) matches age>35 but should be excluded by access criteria
            assertThat(content).noneMatch(m -> "BANNED".equals(m.getStatus()));
        }

        @Test
        @DisplayName("access criteria combined with user query gives intersection")
        void accessCriteriaCombinedWithUserQueryGivesIntersection() {
            Pageable pageable = PageRequest.of(0, 10);

            // Filter for age > 22 — but BANNED entity (age=40) should still be excluded
            Page<SampleServiceModel> result = softDeleteService.find(pageable, "age>22");

            List<SampleServiceModel> content = result.getContent().stream()
                    .filter(java.util.Objects::nonNull)
                    .toList();
            assertThat(content).noneMatch(m -> "BANNED".equals(m.getStatus()));
            assertThat(content).allMatch(m -> m.getAge() > 22);
        }
    }

    @Nested
    @DisplayName("pagination")
    class Pagination {

        @Test
        @DisplayName("should return correct page size")
        void shouldReturnCorrectPageSize() {
            Pageable pageable = PageRequest.of(0, 2);

            Page<SampleServiceModel> result = readOnlyService.find(pageable);

            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getTotalElements()).isEqualTo(5);
            assertThat(result.getTotalPages()).isEqualTo(3);
        }

        @Test
        @DisplayName("should return second page correctly")
        void shouldReturnSecondPageCorrectly() {
            Pageable pageable = PageRequest.of(1, 2);

            Page<SampleServiceModel> result = readOnlyService.find(pageable);

            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("should return last page with remaining elements")
        void shouldReturnLastPageWithRemainingElements() {
            Pageable pageable = PageRequest.of(2, 2);

            Page<SampleServiceModel> result = readOnlyService.find(pageable);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.isLast()).isTrue();
        }
    }

    @Nested
    @DisplayName("findExtended")
    class FindExtended {

        @Test
        @DisplayName("should return extended models with all locale fields")
        void shouldReturnExtendedModelsWithAllLocaleFields() {
            Pageable pageable = PageRequest.of(0, 10);

            Page<SampleServiceExtendedModel> result = readOnlyService.findExtended(pageable);

            assertThat(result.getContent()).hasSize(5);
            assertThat(result.getContent()).allSatisfy(model -> {
                assertThat(model.getNameRU()).isNotNull();
                assertThat(model.getNamePL()).isNotNull();
            });
        }

        @Test
        @DisplayName("should filter extended results with query")
        void shouldFilterExtendedResultsWithQuery() {
            Pageable pageable = PageRequest.of(0, 10);

            Page<SampleServiceExtendedModel> result = readOnlyService.findExtended(pageable, "status==ACTIVE");

            assertThat(result.getContent()).hasSize(4);
            assertThat(result.getContent())
                    .allMatch(m -> "ACTIVE".equals(m.getStatus()));
        }
    }

    @Nested
    @DisplayName("getCount")
    class GetCount {

        @Test
        @DisplayName("should return total count with null query")
        void shouldReturnTotalCountWithNullQuery() {
            long count = readOnlyService.getCount(null);

            assertThat(count).isEqualTo(5);
        }

        @Test
        @DisplayName("should return filtered count with query")
        void shouldReturnFilteredCountWithQuery() {
            long count = readOnlyService.getCount("status==ACTIVE");

            assertThat(count).isEqualTo(4);
        }
    }
}
