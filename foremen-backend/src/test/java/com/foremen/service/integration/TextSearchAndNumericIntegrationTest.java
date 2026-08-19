package com.foremen.service.integration;

import com.foremen.service.integration.dao.SampleEntityDao;
import com.foremen.service.integration.entity.SampleEntity;
import com.foremen.service.integration.entity.SampleServiceModel;
import com.foremen.service.integration.service.SampleReadOnlyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for text search operators (~ct~, ~sw~, ~ew~, ~CT~, ~SW~, ~EW~, ~~)
 * and numeric comparison operators (>, <, >=, <=) against a real PostgreSQL database.
 *
 * Validates Requirements: 5.2, 5.3, 5.4
 */
@SpringBootTest
@Testcontainers
@DisplayName("Text Search and Numeric Operators Integration Tests")
class TextSearchAndNumericIntegrationTest {

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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.liquibase.enabled", () -> "false");
        registry.add("spring.autoconfigure.exclude", () -> "");
    }

    @Autowired
    private SampleReadOnlyService readOnlyService;

    @Autowired
    private SampleEntityDao sampleEntityDao;

    private final Pageable defaultPageable = PageRequest.of(0, 100);

    @BeforeEach
    void setUp() {
        sampleEntityDao.deleteAll();
    }

    @AfterEach
    void tearDown() {
        sampleEntityDao.deleteAll();
    }

    // --- Helper ---

    private SampleEntity createEntity(String nameRU, String namePL, String code,
                                       String city, String country, Integer age, BigDecimal price) {
        SampleEntity entity = new SampleEntity();
        entity.setNameRU(nameRU);
        entity.setNamePL(namePL);
        entity.setCode(code);
        entity.setCity(city);
        entity.setCountry(country);
        entity.setAge(age);
        entity.setPrice(price);
        entity.setStatus("ACTIVE");
        entity.setDeleted(false);
        return sampleEntityDao.save(entity);
    }

    // =====================================================================
    // CASE-INSENSITIVE TEXT SEARCH TESTS
    // =====================================================================

    @Test
    @DisplayName("~ct~ case-insensitive contains: 'nameRU~ct~john' finds entity with nameRU='John Doe'")
    void testCaseInsensitiveContains() {
        createEntity("John Doe", "John Doe", "JD-001", "Berlin", "Germany", 30, BigDecimal.valueOf(10.00));
        createEntity("Jane Smith", "Jane Smith", "JS-002", "Paris", "France", 25, BigDecimal.valueOf(20.00));

        Page<SampleServiceModel> result = readOnlyService.find(defaultPageable, "nameRU~ct~john");

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getName()).isEqualTo("John Doe");
    }

    @Test
    @DisplayName("~sw~ case-insensitive starts with: 'city~sw~war' finds entity with city='Warsaw'")
    void testCaseInsensitiveStartsWith() {
        createEntity("Alice", "Alice", "A-001", "Warsaw", "Poland", 28, BigDecimal.valueOf(15.00));
        createEntity("Bob", "Bob", "B-002", "Berlin", "Germany", 35, BigDecimal.valueOf(25.00));

        Page<SampleServiceModel> result = readOnlyService.find(defaultPageable, "city~sw~war");

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getCity()).isEqualTo("Warsaw");
    }

    @Test
    @DisplayName("~ew~ case-insensitive ends with: 'country~ew~land' finds entity with country='Poland'")
    void testCaseInsensitiveEndsWith() {
        createEntity("Alice", "Alice", "A-001", "Warsaw", "Poland", 28, BigDecimal.valueOf(15.00));
        createEntity("Bob", "Bob", "B-002", "Berlin", "Germany", 35, BigDecimal.valueOf(25.00));

        Page<SampleServiceModel> result = readOnlyService.find(defaultPageable, "country~ew~land");

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getCountry()).isEqualTo("Poland");
    }

    // =====================================================================
    // CASE-SENSITIVE TEXT SEARCH TESTS
    // =====================================================================

    @Test
    @DisplayName("~CT~ case-sensitive contains: 'code~CT~ABC' finds entity with code='ABCdef'")
    void testCaseSensitiveContainsMatch() {
        createEntity("Item1", "Item1", "ABCdef", "Berlin", "Germany", 30, BigDecimal.valueOf(10.00));
        createEntity("Item2", "Item2", "xyzabc", "Paris", "France", 25, BigDecimal.valueOf(20.00));

        Page<SampleServiceModel> result = readOnlyService.find(defaultPageable, "code~CT~ABC");

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getCode()).isEqualTo("ABCdef");
    }

    @Test
    @DisplayName("~CT~ case-sensitive contains: 'code~CT~abc' does NOT find entity with code='ABCdef'")
    void testCaseSensitiveContainsNoMatch() {
        createEntity("Item1", "Item1", "ABCdef", "Berlin", "Germany", 30, BigDecimal.valueOf(10.00));

        Page<SampleServiceModel> result = readOnlyService.find(defaultPageable, "code~CT~abc");

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("~SW~ case-sensitive starts with: 'code~SW~PRJ' finds entity with code='PRJ-123'")
    void testCaseSensitiveStartsWith() {
        createEntity("Project", "Project", "PRJ-123", "Berlin", "Germany", 30, BigDecimal.valueOf(10.00));
        createEntity("Other", "Other", "prj-456", "Paris", "France", 25, BigDecimal.valueOf(20.00));

        Page<SampleServiceModel> result = readOnlyService.find(defaultPageable, "code~SW~PRJ");

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getCode()).isEqualTo("PRJ-123");
    }

    @Test
    @DisplayName("~EW~ case-sensitive ends with: 'code~EW~123' finds entity with code='PRJ-123'")
    void testCaseSensitiveEndsWith() {
        createEntity("Project", "Project", "PRJ-123", "Berlin", "Germany", 30, BigDecimal.valueOf(10.00));
        createEntity("Other", "Other", "PRJ-456", "Paris", "France", 25, BigDecimal.valueOf(20.00));

        Page<SampleServiceModel> result = readOnlyService.find(defaultPageable, "code~EW~123");

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getCode()).isEqualTo("PRJ-123");
    }

    // =====================================================================
    // LEGACY LIKE OPERATOR TEST
    // =====================================================================

    @Test
    @DisplayName("~~ legacy LIKE produces same results as ~ct~ (case-insensitive contains)")
    void testLegacyLikeAlias() {
        createEntity("John Doe", "John Doe", "JD-001", "Berlin", "Germany", 30, BigDecimal.valueOf(10.00));
        createEntity("Jane Smith", "Jane Smith", "JS-002", "Paris", "France", 25, BigDecimal.valueOf(20.00));

        Page<SampleServiceModel> resultLike = readOnlyService.find(defaultPageable, "nameRU~~john");
        Page<SampleServiceModel> resultContains = readOnlyService.find(defaultPageable, "nameRU~ct~john");

        assertThat(resultLike.getContent()).hasSize(1);
        assertThat(resultContains.getContent()).hasSize(1);
        assertThat(resultLike.getContent().getFirst().getName())
                .isEqualTo(resultContains.getContent().getFirst().getName());
    }

    // =====================================================================
    // NUMERIC COMPARISON TESTS (INTEGER)
    // =====================================================================

    @Test
    @DisplayName("> greater than: 'age>25' finds entities with age 30 and 40")
    void testGreaterThanInteger() {
        createEntity("Young", "Young", "Y-001", "Berlin", "Germany", 20, BigDecimal.valueOf(10.00));
        createEntity("Middle", "Middle", "M-001", "Paris", "France", 30, BigDecimal.valueOf(20.00));
        createEntity("Senior", "Senior", "S-001", "London", "UK", 40, BigDecimal.valueOf(30.00));

        Page<SampleServiceModel> result = readOnlyService.find(defaultPageable, "age>25");

        assertThat(result.getContent()).hasSize(2);
        List<Integer> ages = result.getContent().stream().map(SampleServiceModel::getAge).sorted().toList();
        assertThat(ages).containsExactly(30, 40);
    }

    @Test
    @DisplayName("< less than: 'age<35' finds entities with age 20 and 30")
    void testLessThanInteger() {
        createEntity("Young", "Young", "Y-001", "Berlin", "Germany", 20, BigDecimal.valueOf(10.00));
        createEntity("Middle", "Middle", "M-001", "Paris", "France", 30, BigDecimal.valueOf(20.00));
        createEntity("Senior", "Senior", "S-001", "London", "UK", 40, BigDecimal.valueOf(30.00));

        Page<SampleServiceModel> result = readOnlyService.find(defaultPageable, "age<35");

        assertThat(result.getContent()).hasSize(2);
        List<Integer> ages = result.getContent().stream().map(SampleServiceModel::getAge).sorted().toList();
        assertThat(ages).containsExactly(20, 30);
    }

    @Test
    @DisplayName(">= greater than or equal: 'age>=30' finds entities with age 30 and 40")
    void testGreaterThanOrEqualInteger() {
        createEntity("Young", "Young", "Y-001", "Berlin", "Germany", 20, BigDecimal.valueOf(10.00));
        createEntity("Middle", "Middle", "M-001", "Paris", "France", 30, BigDecimal.valueOf(20.00));
        createEntity("Senior", "Senior", "S-001", "London", "UK", 40, BigDecimal.valueOf(30.00));

        Page<SampleServiceModel> result = readOnlyService.find(defaultPageable, "age>=30");

        assertThat(result.getContent()).hasSize(2);
        List<Integer> ages = result.getContent().stream().map(SampleServiceModel::getAge).sorted().toList();
        assertThat(ages).containsExactly(30, 40);
    }

    @Test
    @DisplayName("<= less than or equal: 'age<=30' finds entities with age 20 and 30")
    void testLessThanOrEqualInteger() {
        createEntity("Young", "Young", "Y-001", "Berlin", "Germany", 20, BigDecimal.valueOf(10.00));
        createEntity("Middle", "Middle", "M-001", "Paris", "France", 30, BigDecimal.valueOf(20.00));
        createEntity("Senior", "Senior", "S-001", "London", "UK", 40, BigDecimal.valueOf(30.00));

        Page<SampleServiceModel> result = readOnlyService.find(defaultPageable, "age<=30");

        assertThat(result.getContent()).hasSize(2);
        List<Integer> ages = result.getContent().stream().map(SampleServiceModel::getAge).sorted().toList();
        assertThat(ages).containsExactly(20, 30);
    }

    // =====================================================================
    // NUMERIC COMPARISON TESTS (DECIMAL)
    // =====================================================================

    @Test
    @DisplayName("> greater than decimal: 'price>19.99' finds entities with higher prices")
    void testGreaterThanDecimal() {
        createEntity("Cheap", "Cheap", "C-001", "Berlin", "Germany", 20, BigDecimal.valueOf(9.99));
        createEntity("Medium", "Medium", "M-001", "Paris", "France", 30, BigDecimal.valueOf(19.99));
        createEntity("Expensive", "Expensive", "E-001", "London", "UK", 40, BigDecimal.valueOf(29.99));

        Page<SampleServiceModel> result = readOnlyService.find(defaultPageable, "price>19.99");

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getPrice()).isEqualByComparingTo(BigDecimal.valueOf(29.99));
    }
}
