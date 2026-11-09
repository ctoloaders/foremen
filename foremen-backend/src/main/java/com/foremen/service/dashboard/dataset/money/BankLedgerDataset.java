package com.foremen.service.dashboard.dataset.money;

import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.LedgerChannel;
import com.foremen.dao.model.LedgerOrigin;
import com.foremen.dao.model.LinkStatus;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import com.foremen.service.dashboard.query.QueryContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code BANK_LEDGER}: company-wide ledger lines, ignored lines excluded. Counterparty IBAN/key,
 * raw payloads, ignore reasons and external ids are not exposed.
 */
@Component
public class BankLedgerDataset extends MoneyDataset {

    public BankLedgerDataset() {
        super("BANK_LEDGER", "BANK_LEDGER", BankTransactionEntity.class, "operationDate", List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("account.name", FieldType.STRING),
                money("amount", "amount", "currency"),
                FieldDescriptor.of("currency", FieldType.STRING),
                FieldDescriptor.of("operationDate", FieldType.DATE),
                FieldDescriptor.of("bookingDate", FieldType.DATE),
                enumField("status", BankTxnStatus.class),
                enumField("channel", LedgerChannel.class),
                enumField("origin", LedgerOrigin.class),
                FieldDescriptor.of("counterpartyName", FieldType.STRING),
                FieldDescriptor.of("title", FieldType.STRING),
                FieldDescriptor.computed("direction", FieldType.ENUM, (cb, q, root) -> {
                    Expression<BigDecimal> amount = root.get("amount");
                    return cb.<String>selectCase()
                            .when(cb.greaterThan(amount, BigDecimal.ZERO), "IN")
                            .otherwise("OUT");
                }).withEnumValues(List.of("IN", "OUT"))));
    }

    @Override
    public Predicate extraPredicate(QueryContext ctx, CriteriaBuilder cb, Root<?> root) {
        return cb.notEqual(root.get("linkStatus"), LinkStatus.IGNORED);
    }
}
