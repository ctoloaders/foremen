package com.foremen.controller.integration;

import com.foremen.dao.MaterialSellerDao;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.service.nip.NipLookupResult;
import com.foremen.service.nip.NipLookupService;
import com.foremen.service.nip.NipLookupUnavailableException;
import com.foremen.testsupport.MockMvcSecurityConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FOR-11-00 task 11.1 — NIP handling on {@code /api/material-sellers}: normalization, checksum
 * validation, uniqueness (create/update), blank-to-null, filter/sort by {@code nip}, and the
 * {@code /lookup} outcomes (DICTIONARY / REGISTRY / NONE / 502 / 400).
 *
 * <p>The {@link NipLookupService} is a Mockito spy over the real (disabled) service: by default the
 * shipped disabled behaviour is exercised, and individual tests stub {@code isConfigured}/{@code lookup}.
 *
 * <p>Repeatability: every test generates fresh checksum-valid NIPs and runs in a rolled-back
 * transaction.
 *
 * <p>Validates Requirements 4.*, 5.*, 6.*, 18.1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class MaterialSellerNipIntegrationTest {

    private static final String BASE = "/api/material-sellers";
    private static final String PL_INVALID = "Nieprawidłowy numer NIP.";
    private static final String PL_DUPLICATE = "Sprzedawca z tym NIP już istnieje.";
    private static final String PL_UNAVAILABLE = "Usługa wyszukiwania NIP jest niedostępna.";
    private static final int[] WEIGHTS = {6, 5, 7, 2, 3, 4, 5, 6, 7};

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MaterialSellerDao materialSellerDao;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoSpyBean
    private NipLookupService nipLookupService;

    // --- data generators ---

    private static String uniqueCode() {
        return "nip" + System.nanoTime() + COUNTER.incrementAndGet();
    }

    /** Random checksum-valid 10-digit NIP. */
    private static String validNip() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        while (true) {
            int[] d = new int[10];
            int sum = 0;
            d[0] = rnd.nextInt(1, 10);
            for (int i = 1; i < 9; i++) {
                d[i] = rnd.nextInt(10);
            }
            for (int i = 0; i < 9; i++) {
                sum += WEIGHTS[i] * d[i];
            }
            int check = sum % 11;
            if (check == 10) {
                continue;
            }
            d[9] = check;
            StringBuilder sb = new StringBuilder();
            for (int x : d) {
                sb.append(x);
            }
            return sb.toString();
        }
    }

    /** A 10-digit string that fails the checksum. */
    private static String invalidNip() {
        String valid = validNip();
        int last = valid.charAt(9) - '0';
        return valid.substring(0, 9) + ((last + 1) % 10);
    }

    private static String spaced(String nip) {
        return nip.substring(0, 3) + "-" + nip.substring(3, 6) + " " + nip.substring(6, 8) + "-" + nip.substring(8);
    }

    // --- helpers ---

    private static String json(String code, String nip) {
        String nipPart = nip == null ? "" : ", \"nip\": \"" + nip + "\"";
        return """
                {"code": "%s", "nameRU": "Продавец", "namePL": "Sprzedawca", "active": true%s}
                """.formatted(code, nipPart);
    }

    private static String updateJson(String nip) {
        String nipPart = nip == null ? "" : ", \"nip\": \"" + nip + "\"";
        return """
                {"nameRU": "Продавец 2", "namePL": "Sprzedawca 2", "active": true%s}
                """.formatted(nipPart);
    }

    private ResultActions create(String nip) throws Exception {
        return mockMvc.perform(post(BASE)
                .header("Accept-Language", "pl")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(uniqueCode(), nip)));
    }

    private ResultActions update(Long id, String nip) throws Exception {
        return mockMvc.perform(put(BASE + "/" + id)
                .header("Accept-Language", "pl")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateJson(nip)));
    }

    private MaterialSellerEntity persist(String nip) {
        MaterialSellerEntity s = new MaterialSellerEntity();
        s.setCode(uniqueCode());
        s.setNameRU("Продавец");
        s.setNamePL("Sprzedawca");
        s.setActive(true);
        s.setNip(nip);
        MaterialSellerEntity saved = materialSellerDao.save(s);
        entityManager.flush();
        return saved;
    }

    private ResultActions lookup(String nip) throws Exception {
        return mockMvc.perform(get(BASE + "/lookup")
                .header("Accept-Language", "pl")
                .param("nip", nip));
    }

    // --- create / update with valid NIP ---

    @Test
    @DisplayName("POST - valid NIP is stored normalized (spaces/dashes stripped)")
    void create_validNip_isNormalized() throws Exception {
        String nip = validNip();
        create(spaced(nip))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nip").value(nip));
    }

    @Test
    @DisplayName("PUT - valid NIP is stored normalized")
    void update_validNip_isNormalized() throws Exception {
        MaterialSellerEntity s = persist(null);
        String nip = validNip();
        update(s.getId(), spaced(nip))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nip").value(nip));
        mockMvc.perform(get(BASE + "/" + s.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nip").value(nip));
    }

    // --- invalid NIP ---

    @Test
    @DisplayName("POST - invalid NIP (bad checksum) -> 400 error.materialSeller.nip.invalid")
    void create_invalidChecksum_returns400() throws Exception {
        create(invalidNip())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PL_INVALID));
    }

    @Test
    @DisplayName("POST - non-numeric / wrong length NIP -> 400")
    void create_malformedNip_returns400() throws Exception {
        create("12345").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PL_INVALID));
        create("ABCDEFGHIJ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PL_INVALID));
    }

    @Test
    @DisplayName("PUT - invalid NIP -> 400 error.materialSeller.nip.invalid")
    void update_invalidNip_returns400() throws Exception {
        MaterialSellerEntity s = persist(null);
        update(s.getId(), invalidNip())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PL_INVALID));
    }

    // --- duplicate NIP ---

    @Test
    @DisplayName("POST - duplicate NIP -> 409 error.materialSeller.nip.duplicate (also when formatted differently)")
    void create_duplicateNip_returns409() throws Exception {
        String nip = validNip();
        persist(nip);
        create(nip).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(PL_DUPLICATE));
        create(spaced(nip)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(PL_DUPLICATE));
    }

    @Test
    @DisplayName("PUT - NIP of another seller -> 409; own NIP unchanged -> 200")
    void update_duplicateNip_returns409_selfAllowed() throws Exception {
        String nipA = validNip();
        String nipB = validNip();
        MaterialSellerEntity a = persist(nipA);
        MaterialSellerEntity b = persist(nipB);

        update(b.getId(), nipA).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(PL_DUPLICATE));

        update(a.getId(), nipA).andExpect(status().isOk())
                .andExpect(jsonPath("$.nip").value(nipA));
    }

    // --- blank NIP ---

    @Test
    @DisplayName("POST/PUT - blank NIP is stored as null; several sellers may have no NIP")
    void blankNip_storedAsNull() throws Exception {
        create("").andExpect(status().isOk())
                .andExpect(jsonPath("$.nip").value(nullValue()));
        create("   ").andExpect(status().isOk())
                .andExpect(jsonPath("$.nip").value(nullValue()));

        MaterialSellerEntity s = persist(validNip());
        update(s.getId(), "  ").andExpect(status().isOk())
                .andExpect(jsonPath("$.nip").value(nullValue()));
    }

    // --- filter / sort ---

    @Test
    @DisplayName("GET list - filter by nip==<nip> returns only the matching seller")
    void list_filterByNip() throws Exception {
        String nip = validNip();
        MaterialSellerEntity s = persist(nip);
        persist(validNip());

        mockMvc.perform(get(BASE)
                        .param("query", "nip==" + nip)
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(s.getId()))
                .andExpect(jsonPath("$.content[0].nip").value(nip));
    }

    @Test
    @DisplayName("GET list - sort=nip,asc|desc orders by NIP")
    void list_sortByNip() throws Exception {
        String lo = "5260250274"; // known valid NIPs
        String hi = "7740001454";
        // Narrow to the two sellers via filter so pre-seeded rows do not interfere.
        MaterialSellerEntity a = persist(hi);
        MaterialSellerEntity b = persist(lo);

        String q = "nip~in~" + lo + "," + hi;
        mockMvc.perform(get(BASE).param("query", q).param("sort", "nip,asc")
                        .param("page", "0").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].nip").value(contains(lo, hi)));
        mockMvc.perform(get(BASE).param("query", q).param("sort", "nip,desc")
                        .param("page", "0").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].nip").value(contains(hi, lo)));
    }

    // --- lookup ---

    @Test
    @DisplayName("GET /lookup - existing seller -> DICTIONARY (registry disabled)")
    void lookup_dictionary() throws Exception {
        String nip = validNip();
        MaterialSellerEntity s = persist(nip);
        lookup(spaced(nip))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("DICTIONARY"))
                .andExpect(jsonPath("$.nip").value(nip))
                .andExpect(jsonPath("$.seller.id").value(s.getId()))
                .andExpect(jsonPath("$.registryAvailable").value(false));
    }

    @Test
    @DisplayName("GET /lookup - unknown NIP with the default disabled service -> NONE, registryAvailable=false")
    void lookup_noneWhenRegistryDisabled() throws Exception {
        lookup(validNip())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("NONE"))
                .andExpect(jsonPath("$.registryAvailable").value(false))
                .andExpect(jsonPath("$.seller").value(nullValue()))
                .andExpect(jsonPath("$.candidate").value(nullValue()));
    }

    @Test
    @DisplayName("GET /lookup - configured registry with a hit -> REGISTRY candidate, nothing persisted")
    void lookup_registryHit() throws Exception {
        String nip = validNip();
        Mockito.doReturn(true).when(nipLookupService).isConfigured();
        Mockito.doReturn(Optional.of(new NipLookupResult(nip, "ACME Sp. z o.o.", "ul. Testowa 1, Warszawa",
                        "123456789", "0000123456", NipLookupResult.Registry.KRS)))
                .when(nipLookupService).lookup(nip);

        long before = materialSellerDao.count();
        lookup(nip)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("REGISTRY"))
                .andExpect(jsonPath("$.registryAvailable").value(true))
                .andExpect(jsonPath("$.candidate.name").value("ACME Sp. z o.o."))
                .andExpect(jsonPath("$.candidate.address").value("ul. Testowa 1, Warszawa"))
                .andExpect(jsonPath("$.candidate.registry").value("KRS"))
                .andExpect(jsonPath("$.seller").value(nullValue()));
        org.junit.jupiter.api.Assertions.assertEquals(before, materialSellerDao.count());
    }

    @Test
    @DisplayName("GET /lookup - configured registry without a hit -> NONE, registryAvailable=true")
    void lookup_registryMiss() throws Exception {
        String nip = validNip();
        Mockito.doReturn(true).when(nipLookupService).isConfigured();
        Mockito.doReturn(Optional.empty()).when(nipLookupService).lookup(nip);

        lookup(nip)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("NONE"))
                .andExpect(jsonPath("$.registryAvailable").value(true));
    }

    @Test
    @DisplayName("GET /lookup - registry failure -> 502 error.nipLookup.unavailable")
    void lookup_registryUnavailable_returns502() throws Exception {
        String nip = validNip();
        Mockito.doReturn(true).when(nipLookupService).isConfigured();
        Mockito.doThrow(new NipLookupUnavailableException("boom")).when(nipLookupService).lookup(nip);

        lookup(nip)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value(PL_UNAVAILABLE));
    }

    @Test
    @DisplayName("GET /lookup - invalid NIP -> 400 error.materialSeller.nip.invalid")
    void lookup_invalidNip_returns400() throws Exception {
        lookup(invalidNip())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PL_INVALID));
    }
}
