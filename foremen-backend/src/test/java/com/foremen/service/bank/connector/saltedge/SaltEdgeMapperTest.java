package com.foremen.service.bank.connector.saltedge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.service.bank.connector.HolderInfo;
import com.foremen.service.bank.connector.NormalizedAccount;
import com.foremen.service.bank.connector.NormalizedTransaction;
import com.foremen.service.bank.connector.ProviderBank;
import com.foremen.service.bank.connector.TransactionPage;
import com.foremen.service.bank.connector.TxnStatus;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FOR-07-04 task 5.2: {@link SaltEdgeMapper} against JSON fixtures shaped like the Salt Edge v6
 * responses of {@code fakebank_simple_xf} and the providers listing
 * ({@code src/test/resources/saltedge/client/}).
 *
 * <p><b>Validates: Requirements 9.2, 11.1, 11.3, 11.4</b>
 */
class SaltEdgeMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode fixture(String name) {
        try (InputStream in = SaltEdgeMapperTest.class.getResourceAsStream("/saltedge/client/" + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> Map<String, T> byKey(List<T> rows, Function<T, String> key) {
        return rows.stream().collect(Collectors.toMap(key, Function.identity()));
    }

    @Nested
    class Accounts {

        private final SaltEdgeMapper.Mapped<NormalizedAccount> mapped =
                SaltEdgeMapper.toAccounts(fixture("accounts-fakebank_simple_xf.json"));
        private final Map<String, NormalizedAccount> accounts = byKey(mapped.rows(), NormalizedAccount::externalId);

        @Test
        void mapsFullAccountPerDesignTable() {
            NormalizedAccount a = accounts.get("1180000000000000001");
            assertThat(a.name()).isEqualTo("Firmowe PLN");
            assertThat(a.nature()).isEqualTo("account");
            assertThat(a.currency()).isEqualTo("PLN");
            assertThat(a.balance()).isEqualByComparingTo("10250.55");
            assertThat(a.availableBalance()).isEqualByComparingTo("9800.00");
            assertThat(a.iban()).isEqualTo("PL61109010140000071219812874");
            assertThat(a.accountNumber()).isEqualTo("61109010140000071219812874");
            // client_name wins over holder_name
            assertThat(a.holderName()).isEqualTo("Foremen Sp. z o.o.");
            assertThat(a.balanceAt()).isEqualTo(Instant.parse("2026-11-05T06:30:12Z"));
        }

        @Test
        void fallsBackToHolderNameAndUpperCasesCurrency() {
            NormalizedAccount card = accounts.get("1180000000000000002");
            assertThat(card.holderName()).isEqualTo("Jan Kowalski");
            assertThat(card.currency()).isEqualTo("EUR");
            assertThat(card.balance()).isEqualByComparingTo("-120.4");
            assertThat(card.iban()).isNull();
            assertThat(card.availableBalance()).isNull();
            assertThat(card.balanceAt()).as("unparseable updated_at is tolerated").isNull();
        }

        @Test
        void toleratesMissingExtraAndSkipsMalformed() {
            NormalizedAccount bare = accounts.get("1180000000000000003");
            assertThat(bare.balance()).isNull();
            assertThat(bare.iban()).isNull();
            assertThat(bare.accountNumber()).isNull();
            assertThat(bare.holderName()).isNull();

            assertThat(accounts).doesNotContainKey("1180000000000000004");
            assertThat(mapped.rows()).hasSize(3);
            assertThat(mapped.skippedMalformed()).isEqualTo(1);
        }
    }

    @Nested
    class Transactions {

        private final TransactionPage page = SaltEdgeMapper.toTransactionPage(
                fixture("transactions-posted-fakebank_simple_xf.json"),
                fixture("transactions-pending-fakebank_simple_xf.json"),
                "1190000000000000108", false);
        private final Map<String, NormalizedTransaction> posted =
                byKey(page.posted(), NormalizedTransaction::externalId);

        @Test
        void pageSplitsPostedAndPendingAndCountsMalformed() {
            assertThat(page.posted()).extracting(NormalizedTransaction::externalId).containsExactly(
                    "1190000000000000101", "1190000000000000102", "1190000000000000103",
                    "1190000000000000104", "1190000000000000105");
            assertThat(page.pending()).extracting(NormalizedTransaction::externalId)
                    .containsExactly("1190000000000000201");
            // bad amount, missing made_on, missing id (posted) + unknown status (pending)
            assertThat(page.skippedMalformed()).isEqualTo(4);
            assertThat(page.nextCursor()).isEqualTo("1190000000000000108");
            assertThat(page.complete()).isFalse();
        }

        @Test
        void incomeUsesPayerAndParsesSpacedIbanFromInformation() {
            NormalizedTransaction t = posted.get("1190000000000000101");
            assertThat(t.status()).isEqualTo(TxnStatus.POSTED);
            assertThat(t.amount()).isEqualByComparingTo("4500.00");
            assertThat(t.currency()).isEqualTo("PLN");
            assertThat(t.operationDate()).isEqualTo(LocalDate.of(2026, 11, 2));
            assertThat(t.bookingDate()).as("extra.posting_date").isEqualTo(LocalDate.of(2026, 11, 3));
            assertThat(t.title()).isEqualTo("Przelew przychodzacy FM-42 · Zaliczka etap 1");
            assertThat(t.providerCategory()).isEqualTo("income");
            assertThat(t.counterpartyName()).isEqualTo("ACME Budownictwo Sp. z o.o.");
            assertThat(t.counterpartyIbanRaw()).isEqualTo("PL27114020040000300201355387");
            assertThat(t.counterpartyRaw().path("side").asText()).isEqualTo("payer");
            assertThat(t.counterpartyRaw().has("payee")).isFalse();
            assertThat(t.providerDuplicate()).isFalse();
            assertThat(t.payload().path("extra").path("information").asText()).isEqualTo("Zaliczka etap 1");
            assertThat(t.payload().path("id").asText()).isEqualTo("1190000000000000101");
        }

        @Test
        void expenseUsesPayeeAndParsesNrbFromInformation() {
            NormalizedTransaction t = posted.get("1190000000000000102");
            assertThat(t.amount()).isEqualByComparingTo("-1500.00");
            assertThat(t.counterpartyName()).isEqualTo("Jan Kowalski");
            assertThat(t.counterpartyIbanRaw()).isEqualTo("PL60102010260000042270201111");
            assertThat(t.bookingDate()).as("fallback made_on").isEqualTo(LocalDate.of(2026, 11, 2));
            assertThat(t.title()).isEqualTo("Wynagrodzenie 10/2026");
        }

        @Test
        void payeeReportedAsAccountNumberBecomesIbanAndInformationBecomesName() {
            NormalizedTransaction t = posted.get("1190000000000000103");
            assertThat(t.counterpartyIbanRaw()).isEqualTo("PL71124010370000222233334444");
            assertThat(t.counterpartyName()).isEqualTo("Hurtownia Budowlana Kowal");
        }

        @Test
        void duplicatedFlagIsCarriedForIngestToSkip() {
            assertThat(posted.get("1190000000000000104").providerDuplicate()).isTrue();
        }

        @Test
        void rowWithoutExtraIsTolerated() {
            NormalizedTransaction t = posted.get("1190000000000000105");
            assertThat(t.counterpartyName()).isNull();
            assertThat(t.counterpartyIbanRaw()).isNull();
            assertThat(t.counterpartyRaw()).isNull();
            assertThat(t.bookingDate()).isEqualTo(LocalDate.of(2026, 11, 5));
            assertThat(t.title()).isEqualTo("Oplata za prowadzenie rachunku");
        }

        @Test
        void pendingHasNoBookingDate() {
            NormalizedTransaction t = page.pending().get(0);
            assertThat(t.status()).isEqualTo(TxnStatus.PENDING);
            assertThat(t.bookingDate()).isNull();
            assertThat(t.operationDate()).isEqualTo(LocalDate.of(2026, 11, 5));
            assertThat(t.counterpartyName()).isEqualTo("Castorama Polska");
        }

        @Test
        void maxIdIsNumericMaximumOfPostedRows() {
            assertThat(SaltEdgeMapper.maxId(page.posted())).isEqualTo("1190000000000000105");
            assertThat(SaltEdgeMapper.maxId(List.of())).isNull();
        }

        @Test
        void singleMalformedRowThrowsTypedException() {
            JsonNode bad = JSON.createObjectNode().put("id", "1").put("status", "posted");
            assertThatThrownBy(() -> SaltEdgeMapper.toTransaction(bad))
                    .isInstanceOf(SaltEdgeMapper.MalformedRowException.class);
        }

        @Test
        void emptyOrNullListingsMapToEmptyPage() {
            TransactionPage empty = SaltEdgeMapper.toTransactionPage(null, JSON.createObjectNode(), null, true);
            assertThat(empty.posted()).isEmpty();
            assertThat(empty.pending()).isEmpty();
            assertThat(empty.skippedMalformed()).isZero();
            assertThat(empty.complete()).isTrue();
        }
    }

    @Nested
    class AccountNumberParsing {

        @Test
        void prefersValidCandidateAndIgnoresTrailingWord() {
            assertThat(SaltEdgeMapper.findAccountNumber("rach. PL61 1090 1014 0000 0712 1981 2874 UL PROSTA"))
                    .isEqualTo("PL61109010140000071219812874");
            assertThat(SaltEdgeMapper.findAccountNumber("DE89 3704 0044 0532 0130 00 Berlin"))
                    .isEqualTo("DE89370400440532013000");
        }

        @Test
        void returnsInvalidCandidateCleanedWhenNoValidOne() {
            assertThat(SaltEdgeMapper.findAccountNumber("acct 00 1111 2222 3333 4444 5555 6666"))
                    .isEqualTo("PL00111122223333444455556666");
        }

        @Test
        void noAccountInText() {
            assertThat(SaltEdgeMapper.findAccountNumber("Sample payer information")).isNull();
            assertThat(SaltEdgeMapper.findAccountNumber(null)).isNull();
        }
    }

    @Nested
    class Providers {

        @Test
        void clientModeFlagsBanksAbsentFromKeyOwnerSaltedgeListing() {
            Set<String> saltEdgeKeys = SaltEdgeMapper.providerCodes(fixture("providers-pl-key-owner-saltedge.json"));
            SaltEdgeMapper.Mapped<ProviderBank> mapped =
                    SaltEdgeMapper.toProviderBanks(fixture("providers-pl.json"), saltEdgeKeys);
            Map<String, ProviderBank> banks = byKey(mapped.rows(), ProviderBank::code);

            assertThat(mapped.skippedMalformed()).isEqualTo(1);
            assertThat(banks.keySet()).containsExactlyInAnyOrder(
                    "pko_bp_oauth_client_pl", "erste_oauth_client_pl", "mbank_pl");
            assertThat(banks.get("pko_bp_oauth_client_pl").requiresOwnKeys()).isTrue();
            assertThat(banks.get("erste_oauth_client_pl").requiresOwnKeys()).isFalse();
            assertThat(banks.get("mbank_pl").requiresOwnKeys()).isFalse();
        }

        @Test
        void mapsCapabilities() {
            ProviderBank pko = byKey(SaltEdgeMapper.toProviderBanks(fixture("providers-pl.json"), null).rows(),
                    ProviderBank::code).get("pko_bp_oauth_client_pl");
            assertThat(pko.name()).isEqualTo("PKO Bank Polski");
            assertThat(pko.logoUrl()).endsWith("/pl/pko_bp.svg");
            assertThat(pko.homeUrl()).isEqualTo("https://www.pkobp.pl");
            assertThat(pko.status()).isEqualTo("active");
            assertThat(pko.mode()).isEqualTo("oauth");
            assertThat(pko.regulated()).isTrue();
            assertThat(pko.accountTypes()).containsExactlyInAnyOrder("personal", "business");
            assertThat(pko.fetchScopes()).contains("holder_info");
            assertThat(pko.automaticFetch()).isTrue();
            assertThat(pko.maxConsentDays()).isEqualTo(180);
            assertThat(pko.maxFetchInterval()).isEqualTo(730);
            assertThat(pko.requiresOwnKeys()).as("PARTNER mode never requires own keys").isFalse();
            assertThat(pko.testBank()).isFalse();
        }

        @Test
        void toleratesMissingAisFields() {
            Map<String, ProviderBank> banks = byKey(
                    SaltEdgeMapper.toProviderBanks(fixture("providers-pl.json"), null).rows(), ProviderBank::code);
            ProviderBank mbank = banks.get("mbank_pl");
            assertThat(mbank.accountTypes()).isEmpty();
            assertThat(mbank.fetchScopes()).isEmpty();
            assertThat(mbank.regulated()).isFalse();
            assertThat(mbank.automaticFetch()).isFalse();
            assertThat(mbank.maxConsentDays()).isNull();
            assertThat(mbank.logoUrl()).isNull();
            ProviderBank erste = banks.get("erste_oauth_client_pl");
            assertThat(erste.maxConsentDays()).as("numeric string").isEqualTo(90);
            assertThat(erste.maxFetchInterval()).isNull();
        }

        @Test
        void fakeAndSandboxProvidersAreTestBanks() {
            Map<String, ProviderBank> banks = byKey(
                    SaltEdgeMapper.toProviderBanks(fixture("providers-xf.json"), null).rows(), ProviderBank::code);
            assertThat(banks.get("fakebank_simple_xf").testBank()).isTrue();
            assertThat(banks.get("fake_oauth_client_xf").testBank()).isTrue();
            assertThat(banks.get("pko_sandbox_pl").testBank()).as("name contains Sandbox").isTrue();
            assertThat(banks.get("fakebank_simple_xf").maxFetchInterval()).isEqualTo(365);
        }
    }

    @Nested
    class Holder {

        @Test
        void mapsNamesAndTaxIdFromConnection() {
            HolderInfo h = SaltEdgeMapper.toHolderInfo(fixture("connection-with-holder-info.json"));
            assertThat(h.names()).containsExactly("Foremen Sp. z o.o.");
            assertThat(h.taxIds()).containsExactly("5250000000");
        }

        @Test
        void holderInfoObjectDirectlyAndMissingData() {
            JsonNode direct = fixture("connection-with-holder-info.json").path("data").path("holder_info");
            assertThat(SaltEdgeMapper.toHolderInfo(direct).names()).containsExactly("Foremen Sp. z o.o.");

            JsonNode noHolder = JSON.createObjectNode().set("data", JSON.createObjectNode().put("id", "1"));
            assertThat(SaltEdgeMapper.toHolderInfo(noHolder).isEmpty()).isTrue();
            assertThat(SaltEdgeMapper.toHolderInfo(null).isEmpty()).isTrue();
        }
    }

    @Test
    void amountsKeepExactDecimalScale() {
        JsonNode row = JSON.createObjectNode()
                .put("id", "9").put("status", "posted").put("made_on", "2026-11-01")
                .put("amount", new BigDecimal("-0.10")).put("currency_code", "PLN");
        assertThat(SaltEdgeMapper.toTransaction(row).amount()).isEqualByComparingTo("-0.10");
    }
}
