package com.foremen.controller.bank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.controller.advice.ForemenControllerAdvice;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.FeedProvider;
import com.foremen.service.bank.connection.BankConnectionService;
import com.foremen.service.bank.connector.stub.StubDataStore;

/**
 * FOR-07-04 task 7.1 — {@link StubBankFeedController} (R4.4, R21.5): seeds the real
 * {@link StubDataStore} ({@code 204}), rejects non-STUB connections / invalid seeds, and the bean
 * exists only with {@code foremen.bank-feed.stub.enabled=true}.
 */
class StubBankFeedControllerTest {

    private StubDataStore store;
    private BankConnectionService connectionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        store = new StubDataStore();
        connectionService = mock(BankConnectionService.class);
        ForemenControllerAdvice advice = new ForemenControllerAdvice(new MessageResolver(new StaticMessageSource()));
        mockMvc = MockMvcBuilders.standaloneSetup(new StubBankFeedController(store, connectionService))
                .setControllerAdvice(advice).build();
    }

    @Test
    @DisplayName("POST /stub/connections/{id}/data → 204 and the store holds the seeded account + transaction")
    void seeds() throws Exception {
        when(connectionService.get(7L)).thenReturn(connection(7L, FeedProvider.STUB));

        mockMvc.perform(post("/api/bank/stub/connections/7/data").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "mode": "REPLACE",
                                  "state": { "status": "ACTIVE", "consentExpiresAt": "2027-05-01T00:00:00Z" },
                                  "holder": { "names": ["Foremen Sp. z o.o."], "taxIds": ["5250000000"] },
                                  "accounts": [ { "externalId": "acc-1", "name": "Firmowe PLN", "nature": "account",
                                                  "iban": "PL61109010140000071219812874", "currency": "PLN",
                                                  "balance": 10000.00 } ],
                                  "transactions": [ { "accountExternalId": "acc-1", "externalId": "t-1",
                                                      "status": "POSTED", "amount": -1500.00, "currency": "PLN",
                                                      "operationDate": "2026-11-02", "bookingDate": "2026-11-02",
                                                      "title": "Wynagrodzenie" } ] }
                                """))
                .andExpect(status().isNoContent());

        assertThat(store.accounts(7L)).extracting(a -> a.externalId()).containsExactly("acc-1");
        assertThat(store.transactions(7L, "acc-1")).hasSize(1);
    }

    @Test
    @DisplayName("a non-STUB connection → 409; a seed without ids → 400")
    void rejects() throws Exception {
        when(connectionService.get(8L)).thenReturn(connection(8L, FeedProvider.SALT_EDGE));
        mockMvc.perform(post("/api/bank/stub/connections/8/data").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(StubBankFeedController.NOT_STUB_CONNECTION));

        when(connectionService.get(7L)).thenReturn(connection(7L, FeedProvider.STUB));
        mockMvc.perform(post("/api/bank/stub/connections/7/data").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accounts\":[{\"name\":\"no id\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(StubBankFeedController.SEED_INVALID));
    }

    @Test
    @DisplayName("the controller bean is registered only when foremen.bank-feed.stub.enabled=true")
    void conditionalBean() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(StubDataStore.class, StubDataStore::new)
                .withBean(BankConnectionService.class, () -> connectionService)
                .withUserConfiguration(StubBankFeedController.class);
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(StubBankFeedController.class));
        runner.withPropertyValues("foremen.bank-feed.stub.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(StubBankFeedController.class));
        runner.withPropertyValues("foremen.bank-feed.stub.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(StubBankFeedController.class));
    }

    private static BankConnectionEntity connection(Long id, FeedProvider provider) {
        BankConnectionEntity c = new BankConnectionEntity();
        c.setId(id);
        c.setProvider(provider);
        return c;
    }
}
