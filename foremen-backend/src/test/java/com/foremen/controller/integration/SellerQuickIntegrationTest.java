package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.foremen.dao.MaterialSellerDao;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.UserEntity;

/**
 * FOR-11-00 task 11.6 - {@code POST /api/material-sellers/quick} (Requirement 20.1 - 20.4).
 *
 * <p>Liquibase is off, so the changeset-230 grants for {@code MATERIAL_SELLERS} (BUYER and MANAGER get
 * CREATE; FOREMAN gets nothing) are recreated per test. The concurrent-NIP race is covered at the
 * service level in {@code SellerQuickServiceRaceTest}.
 *
 * <p>Repeatability: unique per-run names, rolled-back transaction.
 *
 * <p>Validates Requirements 20.1, 20.2, 20.3, 20.4.
 */
class SellerQuickIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String QUICK = "/api/material-sellers/quick";
    private static final String NIP_A = "5260250995";
    private static final String NIP_B = "1234563218";

    @Autowired
    private MaterialSellerDao sellerDao;

    @BeforeEach
    void seedSellerGrants() {
        ResourceEntity sellers = resource("MATERIAL_SELLERS");
        OperationEntity create = operation("CREATE");
        OperationEntity read = operation("READ");
        OperationEntity update = operation("UPDATE");
        grant("BUYER", sellers, read, create, update);
        grant("MANAGER", sellers, create);
        em.flush();
        for (String code : List.of("BUYER", "MANAGER", "FOREMAN")) {
            permissionCache.invalidate(code);
        }
    }

    private ResultActions quick(String json, RequestPostProcessor auth) throws Exception {
        var req = post(QUICK).header("Accept-Language", "pl")
                .contentType(MediaType.APPLICATION_JSON).content(json);
        return mockMvc.perform(auth == null ? req : req.with(auth));
    }

    private static String body(String name, String nip) {
        return "{\"name\": \"" + name + "\"" + (nip == null ? "" : ", \"nip\": \"" + nip + "\"") + "}";
    }

    @Test
    @DisplayName("create - 201 created=true, active, names = name, code derived from the name, NIP normalized")
    void create_derivesCode() throws Exception {
        String suffix = unique();
        quick(body("Castorama " + suffix, "526-025-09-95"), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.seller.code").value("castorama_" + suffix))
                .andExpect(jsonPath("$.seller.active").value(true))
                .andExpect(jsonPath("$.seller.nip").value(NIP_A));
        em.flush();
        em.clear();
        MaterialSellerEntity saved = sellerDao.findByNip(NIP_A).orElseThrow();
        assertThat(saved.getNameRU()).isEqualTo("Castorama " + suffix);
        assertThat(saved.getNamePL()).isEqualTo("Castorama " + suffix);
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    @DisplayName("create without NIP - 201, NIP null; the name is trimmed")
    void create_withoutNip() throws Exception {
        String suffix = unique();
        quick(body("  Leroy " + suffix + "  ", null), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.seller.nip").value(nullValue()))
                .andExpect(jsonPath("$.seller.code").value("leroy_" + suffix));
        em.flush();
        assertThat(sellerDao.existsByCode("leroy_" + suffix)).isTrue();
    }

    @Test
    @DisplayName("NIP hit - 200 created=false, the same seller, no duplicate row, formatted NIP matches too")
    void nipHit_returnsExisting() throws Exception {
        String suffix = unique();
        quick(body("Obi " + suffix, NIP_A), null).andExpect(status().isCreated());
        em.flush();
        long before = sellerDao.count();
        Long firstId = sellerDao.findByNip(NIP_A).orElseThrow().getId();

        quick(body("Another name " + suffix, "526 025 09 95"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.seller.id").value(firstId))
                .andExpect(jsonPath("$.seller.code").value("obi_" + suffix));
        assertThat(sellerDao.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("name required - blank, missing and over 100 characters -> 400 nameRequired")
    void nameValidation() throws Exception {
        String key = "error.materialSeller.quick.nameRequired";
        quick("{\"name\": \"   \"}", null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.name").value(key));
        quick("{\"nip\": \"" + NIP_A + "\"}", null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.name").value(key));
        quick(body("x".repeat(101), null), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.name").value(key));
        quick(body("x".repeat(100), null), null).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("invalid NIP (bad checksum or length) -> 400 and nothing is created")
    void invalidNip() throws Exception {
        long before = sellerDao.count();
        quick(body("Bad nip " + unique(), "5260250996"), null).andExpect(status().isBadRequest());
        quick(body("Bad nip " + unique(), "123"), null).andExpect(status().isBadRequest());
        assertThat(sellerDao.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("code collision - the same name gets _2, _3 suffixes; an existing code is never reused")
    void codeCollision_numericSuffix() throws Exception {
        String suffix = unique();
        String name = "Bricoman " + suffix;
        String base = "bricoman_" + suffix;
        quick(body(name, null), null).andExpect(jsonPath("$.seller.code").value(base));
        quick(body(name, NIP_A), null).andExpect(jsonPath("$.seller.code").value(base + "_2"));
        quick(body(name, NIP_B), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.seller.code").value(base + "_3"));
    }

    @Test
    @DisplayName("grants - BUYER and MANAGER may create (201), FOREMAN is rejected (403) and nothing is created")
    void permissions() throws Exception {
        UserEntity buyer = user("BUYER");
        UserEntity manager = user("MANAGER");
        UserEntity foreman = user("FOREMAN");
        long before = sellerDao.count();

        quick(body("Buyer seller " + unique(), null), as(buyer, "BUYER")).andExpect(status().isCreated());
        quick(body("Manager seller " + unique(), null), as(manager, "MANAGER")).andExpect(status().isCreated());
        quick(body("Foreman seller " + unique(), null), as(foreman, "FOREMAN")).andExpect(status().isForbidden());

        assertThat(sellerDao.count()).isEqualTo(before + 2);
    }
}
