package com.foremen.service.bank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankBalanceGapDao;
import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.CompanyProfileDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.BankBalanceGapEntity;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.dao.model.CompanyProfileEntity;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.HolderMatch;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.connector.HolderInfo;
import com.foremen.service.bank.connector.NormalizedAccount;
import com.foremen.service.bank.link.BankLedgerAudit;

/**
 * Unit tests for {@link BankAccountService} (FOR-07-04).
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.4, 3.7, 3.8, 3.9, 3.10, 7.3</b>
 */
@ExtendWith(MockitoExtension.class)
class BankAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final String COMPANY = "Foremen Sp. z o.o.";
    private static final String NIP = "525-000-00-00";

    @Mock private BankAccountDao accountDao;
    @Mock private BankConnectionDao connectionDao;
    @Mock private BankBalanceGapDao gapDao;
    @Mock private CompanyProfileDao companyProfileDao;
    @Mock private BankLedgerAudit audit;

    private final List<BankAccountEntity> accounts = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(10);
    private BankConnectionEntity connection;
    private BankAccountService service;

    @BeforeEach
    void setUp() {
        connection = new BankConnectionEntity();
        connection.setId(1L);
        connection.setProvider(FeedProvider.STUB);
        connection.setStatus(BankConnectionStatus.ACTIVE);
        lenient().when(connectionDao.findById(1L)).thenReturn(Optional.of(connection));
        lenient().when(accountDao.findByConnectionIdOrderByIdAsc(1L)).thenAnswer(inv -> List.copyOf(accounts));
        lenient().when(accountDao.findByTypeOrderByIdAsc(BankAccountType.BANK)).thenAnswer(inv -> List.copyOf(accounts));
        lenient().when(accountDao.save(any())).thenAnswer(inv -> {
            BankAccountEntity a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(ids.incrementAndGet());
                accounts.add(a);
            }
            return a;
        });
        lenient().when(accountDao.findById(any())).thenAnswer(inv ->
                accounts.stream().filter(a -> a.getId().equals(inv.getArgument(0))).findFirst());
        profile(COMPANY, NIP);
        service = new BankAccountService(accountDao, connectionDao, gapDao, companyProfileDao, audit, 0.8);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void profile(String name, String nip) {
        CompanyProfileEntity p = new CompanyProfileEntity();
        p.setFullName(name);
        p.setNip(nip);
        lenient().when(companyProfileDao.findAll()).thenReturn(List.of(p));
    }

    private static NormalizedAccount account(String id, String nature, String holder) {
        return new NormalizedAccount(id, "Konto " + id, nature, "PL61 1090 1014 0000 0712 1981 2874", null, "pln",
                new BigDecimal("100.5"), new BigDecimal("90"), holder, NOW);
    }

    @Test
    void newAccountsDefaultInclusionByNatureAndHolder() {
        BankAccountService.UpsertResult result = service.upsertAccounts(1L, null, List.of(
                account("acc-1", "account", COMPANY),
                account("acc-2", "savings", COMPANY),
                account("acc-3", "checking", "Jan Kowalski"),
                account("acc-4", "card", null)));

        assertThat(result.accountsSeen()).isEqualTo(4);
        assertThat(result.accounts()).allMatch(BankAccountService.AccountUpsert::created);
        BankAccountEntity a1 = accounts.get(0);
        assertThat(a1.getType()).isEqualTo(BankAccountType.BANK);
        assertThat(a1.getIban()).isEqualTo("PL61109010140000071219812874");
        assertThat(a1.getCurrency()).isEqualTo("PLN");
        assertThat(a1.getBalance()).isEqualByComparingTo("100.50");
        assertThat(a1.getHolderMatch()).isEqualTo(HolderMatch.MATCH);
        assertThat(a1.isIncludedInLedger()).isTrue();
        assertThat(accounts.get(1).isIncludedInLedger()).isFalse();            // savings
        assertThat(accounts.get(2).getHolderMatch()).isEqualTo(HolderMatch.MISMATCH);
        assertThat(accounts.get(2).isIncludedInLedger()).isFalse();            // MISMATCH, R3.8
        assertThat(accounts.get(3).getHolderMatch()).isEqualTo(HolderMatch.UNKNOWN);
        assertThat(accounts.get(3).isIncludedInLedger()).isFalse();            // card
    }

    @Test
    void connectionHolderTaxIdMatchesAndIsStored() {
        service.upsertAccounts(1L, new HolderInfo(List.of("Some Other Name"), List.of("PL5250000000")),
                List.of(account("acc-1", "account", null)));

        assertThat(accounts.get(0).getHolderMatch()).isEqualTo(HolderMatch.MATCH);
        assertThat(connection.getHolderNames()).containsEntry("taxIds", List.of("PL5250000000"));
        verify(connectionDao).save(connection);
    }

    @Test
    void upsertUpdatesExistingReturnsPreviousBalanceAndMarksMissingClosed() {
        service.upsertAccounts(1L, null, List.of(account("acc-1", "account", COMPANY), account("acc-2", "account", COMPANY)));
        BankAccountEntity first = accounts.get(0);
        first.setIncludedInLedger(false); // user switched it off
        OffsetDateTime prevAt = first.getBalanceUpdatedAt();

        NormalizedAccount updated = new NormalizedAccount("acc-1", "Renamed", "account", null, null, "PLN",
                new BigDecimal("250.00"), null, COMPANY, NOW.plusSeconds(60));
        BankAccountService.UpsertResult result = service.upsertAccounts(1L, null, List.of(updated));

        BankAccountService.AccountUpsert up = result.accounts().get(0);
        assertThat(up.created()).isFalse();
        assertThat(up.previousBalance()).isEqualByComparingTo("100.50");
        assertThat(up.previousBalanceAt()).isEqualTo(prevAt);
        assertThat(up.balance()).isEqualByComparingTo("250.00");
        assertThat(first.getName()).isEqualTo("Renamed");
        assertThat(first.isIncludedInLedger()).isFalse(); // user choice kept
        assertThat(result.closedAccountIds()).containsExactly(accounts.get(1).getId());
        assertThat(accounts.get(1).isClosedAtProvider()).isTrue();

        service.upsertAccounts(1L, null, List.of(updated, account("acc-2", "account", COMPANY)));
        assertThat(accounts.get(1).isClosedAtProvider()).isFalse();
    }

    @Test
    void malformedRowsAreSkippedAndNotMarkedClosed() {
        service.upsertAccounts(1L, null, List.of(account("acc-1", "account", COMPANY)));
        NormalizedAccount noCurrency = new NormalizedAccount("acc-1", "x", "account", null, null, null,
                null, null, null, null);
        BankAccountService.UpsertResult result = service.upsertAccounts(1L, null, List.of(noCurrency));
        assertThat(result.skippedMalformed()).isEqualTo(1);
        assertThat(accounts.get(0).isClosedAtProvider()).isFalse();
    }

    @Test
    void profileChangeToMismatchKeepsIncludedAccountAndShowsWarning() {
        service.upsertAccounts(1L, null, List.of(account("acc-1", "account", COMPANY)));
        BankAccountEntity a = accounts.get(0);
        a.setHolderOverrideAt(OffsetDateTime.now()); // stale override from an earlier state
        assertThat(a.isIncludedInLedger()).isTrue();

        profile("Inna Firma S.A.", "1111111111");
        assertThat(service.recomputeHolderMatches()).isEqualTo(1);

        assertThat(a.getHolderMatch()).isEqualTo(HolderMatch.MISMATCH);
        assertThat(a.isIncludedInLedger()).isTrue();
        assertThat(a.getHolderOverrideAt()).isNull();
        assertThat(BankAccountService.holderWarning(a)).isTrue();
    }

    @Test
    void noCompanyProfileGivesUnknown() {
        when(companyProfileDao.findAll()).thenReturn(List.of());
        service.upsertAccounts(1L, null, List.of(account("acc-1", "account", "Jan Kowalski")));
        assertThat(accounts.get(0).getHolderMatch()).isEqualTo(HolderMatch.UNKNOWN);
        assertThat(accounts.get(0).isIncludedInLedger()).isTrue();
    }

    @Test
    void includingMismatchRequiresReasonAndRecordsOverride() {
        service.upsertAccounts(1L, null, List.of(account("acc-3", "account", "Jan Kowalski")));
        BankAccountEntity a = accounts.get(0);
        assertThat(a.isIncludedInLedger()).isFalse();

        assertThatThrownBy(() -> service.setIncludedInLedger(a.getId(), true, "  "))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessageCode()).isEqualTo(BankAccountService.REASON_REQUIRED);
                });
        assertThat(a.isIncludedInLedger()).isFalse();

        service.setIncludedInLedger(a.getId(), true, " Konto wspólnika, potwierdzone ");

        assertThat(a.isIncludedInLedger()).isTrue();
        assertThat(a.getHolderOverrideReason()).isEqualTo("Konto wspólnika, potwierdzone");
        assertThat(a.getHolderOverrideBy()).isEqualTo("SYSTEM");
        assertThat(a.getHolderOverrideAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        assertThat(BankAccountService.holderWarning(a)).isFalse();
        verify(audit).write(eq(BankAccountEntity.class), eq(a.getId()), eq("HOLDER_OVERRIDE"), anyMap(), anyMap());
    }

    @Test
    void togglingMatchedAccountNeedsNoReasonAndIsAudited() {
        service.upsertAccounts(1L, null, List.of(account("acc-1", "account", COMPANY)));
        BankAccountEntity a = accounts.get(0);
        service.setIncludedInLedger(a.getId(), false, null);
        assertThat(a.isIncludedInLedger()).isFalse();
        verify(audit).write(eq(BankAccountEntity.class), eq(a.getId()), eq("UPDATE"), anyMap(), anyMap());
    }

    @Test
    void togglingCashRegisterIsRejected() {
        BankAccountEntity cash = new BankAccountEntity();
        cash.setType(BankAccountType.CASH);
        cash.setCurrency("PLN");
        accountDao.save(cash);
        assertThatThrownBy(() -> service.setIncludedInLedger(cash.getId(), false, null))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo(BankAccountService.NOT_BANK));
    }

    @Test
    void acknowledgingLastOpenGapClearsAccountFlag() {
        service.upsertAccounts(1L, null, List.of(account("acc-1", "account", COMPANY)));
        BankAccountEntity a = accounts.get(0);
        a.setHasBalanceGap(true);
        BankBalanceGapEntity gap = new BankBalanceGapEntity();
        gap.setId(7L);
        gap.setAccount(a);
        when(gapDao.findById(7L)).thenReturn(Optional.of(gap));
        when(gapDao.save(gap)).thenReturn(gap);
        when(gapDao.findByAccountIdAndAcknowledgedAtIsNull(a.getId())).thenReturn(List.of(gap));

        service.acknowledgeGap(7L, "  bank fee  ");

        assertThat(gap.getAcknowledgedAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        assertThat(gap.getAcknowledgedBy()).isEqualTo("SYSTEM");
        assertThat(gap.getNote()).isEqualTo("bank fee");
        assertThat(a.isHasBalanceGap()).isFalse();
        verify(audit).write(eq(BankBalanceGapEntity.class), eq(7L), eq("ACKNOWLEDGE"), anyMap(), anyMap());

        // idempotent
        service.acknowledgeGap(7L, "again");
        assertThat(gap.getNote()).isEqualTo("bank fee");
    }

    @Test
    void acknowledgingOneOfTwoGapsKeepsFlag() {
        service.upsertAccounts(1L, null, List.of(account("acc-1", "account", COMPANY)));
        BankAccountEntity a = accounts.get(0);
        a.setHasBalanceGap(true);
        BankBalanceGapEntity gap = new BankBalanceGapEntity();
        gap.setId(7L);
        gap.setAccount(a);
        BankBalanceGapEntity other = new BankBalanceGapEntity();
        other.setId(8L);
        other.setAccount(a);
        when(gapDao.findById(7L)).thenReturn(Optional.of(gap));
        when(gapDao.save(gap)).thenReturn(gap);
        when(gapDao.findByAccountIdAndAcknowledgedAtIsNull(a.getId())).thenReturn(List.of(gap, other));

        service.acknowledgeGap(7L, null);

        assertThat(a.isHasBalanceGap()).isTrue();
    }

    @Test
    void holderMapRoundTrip() {
        HolderInfo info = new HolderInfo(List.of("A", "B"), List.of("123"));
        Map<String, Object> map = BankAccountService.holderMap(info);
        BankConnectionEntity c = new BankConnectionEntity();
        c.setHolderNames(map);
        assertThat(BankAccountService.holderOf(c)).isEqualTo(info);
        assertThat(BankAccountService.holderOf(new BankConnectionEntity()).isEmpty()).isTrue();
    }
}
