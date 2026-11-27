package com.foremen.service.bank.connector.fintable;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** BUG-3: the catalogue keeps the groups of the requested country (Fintable only reorders by it). */
class FintableCatalogueCountryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode groups() throws Exception {
        return JSON.readTree("[{\"name\":\"mBank Retail\",\"countries\":[\"PL\"]},"
                + "{\"name\":\"mBank Retail\",\"countries\":[\"CZ\"]},"
                + "{\"name\":\"mBank - Business\",\"countries\":[\"US\"]},"
                + "{\"name\":\"No countries\"}]");
    }

    private static List<String> names(List<JsonNode> groups) {
        return groups.stream()
                .map(g -> g.path("name").asText() + "/" + g.path("countries").path(0).asText())
                .toList();
    }

    @Test
    void keepsOnlyTheGroupsOfTheRequestedCountry() throws Exception {
        assertThat(names(FintableConnector.inCountry(groups(), "pl"))).containsExactly("mBank Retail/PL");
    }

    @Test
    void keepsEverythingWhenNoGroupMatches() throws Exception {
        assertThat(FintableConnector.inCountry(groups(), "DE")).hasSize(4);
    }

    @Test
    void keepsEverythingWithoutACountry() throws Exception {
        assertThat(FintableConnector.inCountry(groups(), " ")).hasSize(4);
    }
}
