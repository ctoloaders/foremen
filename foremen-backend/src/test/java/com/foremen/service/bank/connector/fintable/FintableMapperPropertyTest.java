package com.foremen.service.bank.connector.fintable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.service.bank.connector.NormalizedTransaction;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-07-04a task 2.3: money exactness, sign / direction, never throws.
 *
 * <p><b>Validates: Requirements 8.4, 8.5, 16.1</b>
 */
class FintableMapperPropertyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Provide
    Arbitrary<BigDecimal> amounts() {
        return Arbitraries.bigDecimals().between(new BigDecimal("-99999999.99"), new BigDecimal("99999999.99"))
                .ofScale(2).filter(a -> a.signum() != 0);
    }

    private static ObjectNode tx(BigDecimal amount) {
        ObjectNode tx = JSON.createObjectNode();
        tx.put("id", "tx_1");
        tx.put("amount", amount.toPlainString());
        tx.put("currency", "PLN");
        tx.put("date", "2026-11-10");
        ObjectNode raw = tx.putObject("raw");
        raw.put("creditorName", "CRED");
        raw.putObject("creditorAccount").put("iban", "PLCRED");
        raw.put("debtorName", "DEBT");
        raw.putObject("debtorAccount").put("iban", "PLDEBT");
        return tx;
    }

    @Property
    void amountIsExactAndSignPicksCounterpartyDirection(@ForAll("amounts") BigDecimal amount) {
        NormalizedTransaction t = FintableMapper.transaction(tx(amount), "conn_nordigen_1");
        assertThat(t).isNotNull();
        assertThat(t.amount()).isEqualTo(amount);
        if (amount.signum() < 0) {
            assertThat(t.counterpartyName()).isEqualTo("CRED");
            assertThat(t.counterpartyIbanRaw()).isEqualTo("PLCRED");
        } else {
            assertThat(t.counterpartyName()).isEqualTo("DEBT");
            assertThat(t.counterpartyIbanRaw()).isEqualTo("PLDEBT");
        }
    }

    @Property
    void neverThrowsOnArbitraryScalarFields(@ForAll List<String> junk) {
        ObjectNode tx = JSON.createObjectNode();
        String[] keys = {"id", "amount", "currency", "date", "auth_date", "description", "raw", "merchant",
                "category", "updated_at"};
        for (int i = 0; i < junk.size(); i++) {
            tx.put(keys[i % keys.length], junk.get(i));
        }
        JsonNode copy = tx;
        NormalizedTransaction t = FintableMapper.transaction(copy, "conn_enablebanking_1");
        FintableMapper.counterparty(copy, new BigDecimal("-1"), "PLAID");
        FintableMapper.account(copy, null);
        FintableMapper.maxUpdatedAt(t == null ? List.of() : List.of(t));
    }
}
