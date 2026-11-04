package com.foremen.service.bank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankBalanceGapDao;
import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.CompanyProfileDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.CompanyProfileEntity;
import com.foremen.dao.model.HolderMatch;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.connector.HolderInfo;
import com.foremen.service.bank.connector.NormalizedAccount;
import com.foremen.service.bank.link.BankLedgerAudit;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property 12 (FOR-07-04), account side: a new {@code MISMATCH} account is never included in the
 * ledger, whatever its nature, and cannot be included without a non-blank override reason.
 *
 * <p>Feature: FOR-07-04-bank-ledger, Property 12: Holder match
 *
 * <p><b>Validates: Requirements 3.2, 3.8</b>
 */
@Tag("Feature: FOR-07-04-bank-ledger, Property 12: Holder match")
class BankAccountInclusionPropertyTest {

    private static final String COMPANY = "Foremen Sp. z o.o.";
    private static final String NIP = "5250000000";

    @Provide
    Arbitrary<String> natures() {
        return Arbitraries.oneOf(
                Arbitraries.of("account", "checking", "current", "ACCOUNT", " Checking ", "savings", "card",
                        "credit_card", "loan", "investment"),
                Arbitraries.strings().alpha().ofMaxLength(12));
    }

    @Provide
    Arbitrary<String> blankReasons() {
        return Arbitraries.of("", " ", "\t", "  \n ").injectNull(0.3);
    }

    @Property(tries = 200)
    void mismatchIsNeverIncludedByDefault(@ForAll("natures") String nature) {
        assertThat(BankAccountService.defaultIncluded(nature, HolderMatch.MISMATCH)).isFalse();
    }

    @Property(tries = 100)
    void newMismatchAccountIsExcludedAndNeedsReason(@ForAll("natures") String nature,
                                                    @ForAll("blankReasons") String reason) {
        Fixture f = new Fixture();
        // Holder present (tax id) but different from the company NIP ⇒ MISMATCH.
        f.service.upsertAccounts(1L, new HolderInfo(List.of(), List.of("9999999999")), List.of(
                new NormalizedAccount("acc", "Konto", nature, null, null, "PLN", BigDecimal.ONE, null, null,
                        Instant.parse("2026-11-10T10:00:00Z"))));
        BankAccountEntity account = f.accounts.get(0);

        assertThat(account.getHolderMatch()).isEqualTo(HolderMatch.MISMATCH);
        assertThat(account.isIncludedInLedger()).isFalse();
        assertThatThrownBy(() -> f.service.setIncludedInLedger(account.getId(), true, reason))
                .isInstanceOf(ForemenApiException.class)
                .hasMessage(BankAccountService.REASON_REQUIRED);
        assertThat(account.isIncludedInLedger()).isFalse();
        assertThat(account.getHolderOverrideAt()).isNull();
    }

    /** Minimal in-memory wiring (no Spring, no DB). */
    private static final class Fixture {
        final List<BankAccountEntity> accounts = new ArrayList<>();
        final BankAccountService service;

        Fixture() {
            AtomicLong ids = new AtomicLong();
            BankAccountDao accountDao = mock(BankAccountDao.class);
            BankConnectionDao connectionDao = mock(BankConnectionDao.class);
            CompanyProfileDao profileDao = mock(CompanyProfileDao.class);
            BankConnectionEntity connection = new BankConnectionEntity();
            connection.setId(1L);
            CompanyProfileEntity profile = new CompanyProfileEntity();
            profile.setFullName(COMPANY);
            profile.setNip(NIP);
            when(connectionDao.findById(1L)).thenReturn(Optional.of(connection));
            when(profileDao.findAll()).thenReturn(List.of(profile));
            when(accountDao.findByConnectionIdOrderByIdAsc(1L)).thenAnswer(inv -> List.copyOf(accounts));
            when(accountDao.save(any())).thenAnswer(inv -> {
                BankAccountEntity a = inv.getArgument(0);
                if (a.getId() == null) {
                    a.setId(ids.incrementAndGet());
                    accounts.add(a);
                }
                return a;
            });
            when(accountDao.findById(any())).thenAnswer(inv ->
                    accounts.stream().filter(a -> a.getId().equals(inv.getArgument(0))).findFirst());
            service = new BankAccountService(accountDao, connectionDao, mock(BankBalanceGapDao.class), profileDao,
                    mock(BankLedgerAudit.class), 0.8);
        }
    }
}
