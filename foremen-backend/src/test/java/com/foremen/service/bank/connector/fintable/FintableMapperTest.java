package com.foremen.service.bank.connector.fintable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.service.bank.connector.NormalizedAccount;
import com.foremen.service.bank.connector.NormalizedTransaction;
import com.foremen.service.bank.connector.ProviderBank;
import com.foremen.service.bank.connector.TxnStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** FOR-07-04a task 2.3: Requirements 4.2, 7.1, 8.4, 8.5, 8.6, 16.1, 16.2. */
class FintableMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode fixture(String name) {
        try (InputStream in = FintableMapperTest.class.getResourceAsStream("/fintable/" + name)) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void plaidAccountMapsTypeBalancesAndNoIban() {
        Instant at = Instant.parse("2026-11-18T11:00:00Z");
        NormalizedAccount a = FintableMapper.account(fixture("plaid-account.json"), at);
        assertThat(a.externalId()).isEqualTo("acc_plaid_sandbox_01");
        assertThat(a.name()).isEqualTo("Plaid Checking 0000");
        assertThat(a.nature()).isEqualTo("account");
        assertThat(a.iban()).isNull();
        assertThat(a.holderName()).isNull();
        assertThat(a.currency()).isEqualTo("USD");
        assertThat(a.balance()).isEqualByComparingTo("110.00");
        assertThat(a.availableBalance()).isEqualByComparingTo("100.00");
        assertThat(a.balanceAt()).isEqualTo(at);
    }

    @Test
    void natureMapFollowsRequirement71() {
        assertThat(FintableMapper.nature("depository / checking")).isEqualTo("account");
        assertThat(FintableMapper.nature("credit / credit card")).isEqualTo("card");
        assertThat(FintableMapper.nature("depository / savings")).isEqualTo("savings");
        assertThat(FintableMapper.nature("depository / money market")).isEqualTo("savings");
        assertThat(FintableMapper.nature("depository / cd")).isEqualTo("savings");
        assertThat(FintableMapper.nature("loan / mortgage")).isEqualTo("account");
        assertThat(FintableMapper.nature(null)).isEqualTo("account");
    }

    @Test
    void accountWithoutIdIsNull() throws Exception {
        assertThat(FintableMapper.account(JSON.readTree("{\"name\":\"x\"}"), null)).isNull();
        assertThat(FintableMapper.account(null, null)).isNull();
    }

    @Test
    void plaidTransactionMapsFieldsAndKeepsPayload() {
        JsonNode tx = fixture("plaid-transaction.json");
        NormalizedTransaction t = FintableMapper.transaction(tx, "conn_plaid_sandbox_01");
        assertThat(t.externalId()).isEqualTo("tx_plaid_sandbox_01");
        assertThat(t.status()).isEqualTo(TxnStatus.POSTED);
        assertThat(t.amount()).isEqualByComparingTo("-89.40");
        assertThat(t.amount().scale()).isEqualTo(2);
        assertThat(t.currency()).isEqualTo("USD");
        assertThat(t.operationDate()).isEqualTo(LocalDate.parse("2026-11-11"));
        assertThat(t.bookingDate()).isEqualTo(LocalDate.parse("2026-11-12"));
        assertThat(t.title()).isEqualTo("Uber 063015 SF**POOL**");
        assertThat(t.providerCategory()).isEqualTo("Travel");
        assertThat(t.counterpartyName()).isEqualTo("Uber");
        assertThat(t.counterpartyIbanRaw()).isNull();
        assertThat(t.providerDuplicate()).isFalse();
        assertThat(t.payload()).isSameAs(tx);
    }

    @Test
    void operationDateFallsBackToDateAndTitleToMemoThenMerchant() throws Exception {
        NormalizedTransaction t = FintableMapper.transaction(JSON.readTree(
                "{\"id\":\"tx_1\",\"amount\":\"1.5\",\"currency\":\"PLN\",\"date\":\"2026-01-02\","
                        + "\"external_memo\":\"memo\",\"merchant\":\"m\"}"));
        assertThat(t.operationDate()).isEqualTo(LocalDate.parse("2026-01-02"));
        assertThat(t.title()).isEqualTo("memo");
        NormalizedTransaction t2 = FintableMapper.transaction(JSON.readTree(
                "{\"id\":\"tx_2\",\"amount\":\"1.5\",\"currency\":\"PLN\",\"date\":\"2026-01-02\",\"merchant\":\"m\"}"));
        assertThat(t2.title()).isEqualTo("m");
        assertThat(t2.counterpartyName()).isEqualTo("m");
    }

    @Test
    void goCardlessOutgoingUsesCreditor() {
        NormalizedTransaction t = FintableMapper.transaction(
                fixture("gocardless-transaction-out.json"), "conn_nordigen_abc");
        assertThat(t.counterpartyName()).isEqualTo("Test Dostawca Sp. z o.o.");
        assertThat(t.counterpartyIbanRaw()).isEqualTo("PL00000000000000000000000001");
        assertThat(t.counterpartyRaw().has("creditorName")).isTrue();
        assertThat(t.counterpartyRaw().has("debtorName")).isFalse();
    }

    @Test
    void goCardlessIncomingUsesDebtor() {
        NormalizedTransaction t = FintableMapper.transaction(
                fixture("gocardless-transaction-in.json"), "conn_nordigen_abc");
        assertThat(t.counterpartyName()).isEqualTo("Test Klient S.A.");
        assertThat(t.counterpartyIbanRaw()).isEqualTo("PL00000000000000000000000003");
    }

    @Test
    void enableBankingOutgoingAndIncoming() {
        NormalizedTransaction out = FintableMapper.transaction(
                fixture("enablebanking-transaction-out.json"), "conn_enablebanking_x");
        assertThat(out.counterpartyName()).isEqualTo("Hurtownia Testowa");
        assertThat(out.counterpartyIbanRaw()).isEqualTo("PL00000000000000000000000004");
        NormalizedTransaction in = FintableMapper.transaction(
                fixture("enablebanking-transaction-in.json"), "conn_enablebanking_x");
        assertThat(in.counterpartyName()).isEqualTo("Inwestor Testowy Sp. z o.o.");
        assertThat(in.counterpartyIbanRaw()).isEqualTo("PL00000000000000000000000005");
    }

    @Test
    void unknownProviderUsesOnlyFallbacks() {
        NormalizedTransaction t = FintableMapper.transaction(fixture("gocardless-transaction-out.json"), "conn_other_1");
        assertThat(t.counterpartyName()).isNull();
        assertThat(t.counterpartyIbanRaw()).isNull();
        NormalizedTransaction noId = FintableMapper.transaction(fixture("gocardless-transaction-out.json"));
        assertThat(noId.counterpartyName()).isNull();
    }

    @Test
    void missingOrOddRawFallsBackToMerchantThenAccountOwnerThenNull() throws Exception {
        String base = "{\"id\":\"t\",\"amount\":\"-1\",\"currency\":\"PLN\",\"date\":\"2026-01-01\"";
        assertThat(FintableMapper.transaction(JSON.readTree(base + ",\"raw\":\"oops\",\"merchant\":\"M\","
                + "\"account_owner\":\"O\"}"), "conn_nordigen_1").counterpartyName()).isEqualTo("M");
        assertThat(FintableMapper.transaction(JSON.readTree(base + ",\"raw\":[],\"account_owner\":\"O\"}"),
                "conn_enablebanking_1").counterpartyName()).isEqualTo("O");
        NormalizedTransaction none = FintableMapper.transaction(JSON.readTree(base
                + ",\"raw\":{\"creditorName\":\"  \",\"creditorAccount\":5}}"), "conn_nordigen_1");
        assertThat(none.counterpartyName()).isNull();
        assertThat(none.counterpartyIbanRaw()).isNull();
    }

    @Test
    void malformedRowsMapToNull() throws Exception {
        for (String json : List.of(
                "null", "[]", "{}",
                "{\"amount\":\"1\",\"currency\":\"PLN\",\"date\":\"2026-01-01\"}",
                "{\"id\":\"t\",\"currency\":\"PLN\",\"date\":\"2026-01-01\"}",
                "{\"id\":\"t\",\"amount\":\"abc\",\"currency\":\"PLN\",\"date\":\"2026-01-01\"}",
                "{\"id\":\"t\",\"amount\":\"1\",\"date\":\"2026-01-01\"}",
                "{\"id\":\"t\",\"amount\":\"1\",\"currency\":\"PLN\",\"date\":\"not-a-date\"}",
                "{\"id\":\"t\",\"amount\":{},\"currency\":\"PLN\",\"date\":\"2026-01-01\"}")) {
            assertThat(FintableMapper.transaction(JSON.readTree(json), "conn_nordigen_1")).as(json).isNull();
        }
        assertThat(FintableMapper.transaction(null)).isNull();
    }

    @Test
    void searchResponseMapsConnectionsAndDropsDefunct() {
        JsonNode group = fixture("institutions-search.json").get("groups").get(0);
        List<ProviderBank> banks = new ArrayList<>();
        for (JsonNode c : group.get("connections")) {
            ProviderBank b = FintableMapper.bank(group, c);
            if (b != null) {
                banks.add(b);
            }
        }
        assertThat(banks).extracting(ProviderBank::code).containsExactly(
                "test-bank-pl-retail-enablebanking", "test-bank-pl-business-nordigen",
                "test-bank-pl-business-and-personal-plaid", "test-bank-sandbox");

        ProviderBank retail = banks.get(0);
        assertThat(retail.name()).isEqualTo("Test Bank Retail");
        assertThat(retail.homeUrl()).isEqualTo("https://testbank.example");
        assertThat(retail.via()).isEqualTo("ENABLEBANKING");
        assertThat(retail.regulated()).isTrue();
        assertThat(retail.mode()).isEqualTo("api");
        assertThat(retail.accountTypes()).isEqualTo(Set.of("personal"));
        assertThat(retail.testBank()).isFalse();

        assertThat(banks.get(1).accountTypes()).isEqualTo(Set.of("business"));
        assertThat(banks.get(1).regulated()).isTrue();
        ProviderBank both = banks.get(2);
        assertThat(both.accountTypes()).isEqualTo(Set.of("personal", "business"));
        assertThat(both.regulated()).isFalse();
        assertThat(banks.get(3).testBank()).isTrue();
    }

    @Test
    void bankWithoutSlugIsNull() throws Exception {
        assertThat(FintableMapper.bank(null, JSON.readTree("{\"name\":\"x\"}"))).isNull();
        assertThat(FintableMapper.bank(null, null)).isNull();
    }

    @Test
    void maxUpdatedAtIsLargestPayloadTimestampOrNull() {
        List<NormalizedTransaction> rows = new ArrayList<>();
        rows.add(FintableMapper.transaction(fixture("plaid-transaction.json"), "conn_plaid_1"));
        rows.add(FintableMapper.transaction(fixture("gocardless-transaction-out.json"), "conn_nordigen_1"));
        rows.add(FintableMapper.transaction(fixture("enablebanking-transaction-out.json"), "conn_enablebanking_1"));
        assertThat(FintableMapper.maxUpdatedAt(rows)).isEqualTo(Instant.parse("2026-11-18T11:04:44Z"));
        assertThat(FintableMapper.maxUpdatedAt(List.of())).isNull();
        assertThat(FintableMapper.maxUpdatedAt(null)).isNull();
    }

    @Test
    void detectProviderFromConnectionId() {
        assertThat(FintableMapper.detectProvider("conn_nordigen_1")).isEqualTo("NORDIGEN");
        assertThat(FintableMapper.detectProvider("conn_enablebanking_1")).isEqualTo("ENABLEBANKING");
        assertThat(FintableMapper.detectProvider("conn_plaid_1")).isEqualTo("PLAID");
        assertThat(FintableMapper.detectProvider("conn_x")).isNull();
        assertThat(FintableMapper.detectProvider(null)).isNull();
    }

    @Test
    void amountKeepsExactDecimalFromText() throws Exception {
        NormalizedTransaction t = FintableMapper.transaction(JSON.readTree(
                "{\"id\":\"t\",\"amount\":\"0.10\",\"currency\":\"PLN\",\"date\":\"2026-01-01\"}"));
        assertThat(t.amount()).isEqualTo(new BigDecimal("0.10"));
    }
}
