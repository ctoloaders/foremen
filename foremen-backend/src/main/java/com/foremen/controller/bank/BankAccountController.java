package com.foremen.controller.bank;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.bank.dto.BalanceGapDto;
import com.foremen.controller.bank.dto.BankAccountDto;
import com.foremen.controller.bank.dto.BankAdminRequests;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.BankBalanceGapEntity;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.account.BankAccountService;
import com.foremen.service.bank.cash.CashAccountDto;
import com.foremen.service.bank.cash.CashAccountRequest;
import com.foremen.service.bank.cash.CashLedgerService;
import com.foremen.service.bank.connection.BankConnectionService;

import lombok.RequiredArgsConstructor;

/**
 * FOR-07-04 (Requirements 3.2, 3.3, 3.8, 7.3, 19.5, 21.1, 21.3, 27.1) — bank and cash accounts.
 *
 * <h2>Endpoints ({@code BANK_CONNECTIONS})</h2>
 * <ul>
 *   <li>{@code GET /api/bank/accounts?connectionId=&type=} (READ) — bank accounts (holder match,
 *       provider balance) followed by cash registers (computed balance). {@code connectionId}
 *       restricts to that connection's bank accounts; {@code type} = {@code BANK} | {@code CASH};</li>
 *   <li>{@code GET /api/bank/accounts/{id}} (READ);</li>
 *   <li>{@code PATCH /api/bank/accounts/{id}} (UPDATE) — {@code {includedInLedger,
 *       holderOverrideReason?}}; including a {@code MISMATCH} account needs a reason
 *       ({@code 400 error.bank.holder.reasonRequired});</li>
 *   <li>{@code GET /api/bank/accounts/{id}/balance-gaps} (READ), newest first;</li>
 *   <li>{@code POST /api/bank/balance-gaps/{id}/acknowledge} (UPDATE) — {@code {note}};</li>
 *   <li>{@code GET /api/bank/cash-accounts[/{id}]} (READ), {@code POST /api/bank/cash-accounts}
 *       (CREATE → 201), {@code PUT /api/bank/cash-accounts/{id}} (UPDATE),
 *       {@code POST /api/bank/cash-accounts/{id}/archive} (UPDATE).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bank")
@RequiredArgsConstructor
@PermissionResource("BANK_CONNECTIONS")
public class BankAccountController {

    /** {@code PATCH /bank/accounts/{id}} without {@code includedInLedger}. */
    public static final String INCLUDED_REQUIRED = "error.bank.account.includedInLedgerRequired";

    private final BankAccountService accountService;
    private final CashLedgerService cashLedgerService;
    private final BankConnectionService connectionService;

    // --- bank + cash accounts ------------------------------------------------------------------

    @GetMapping("/accounts")
    @PermissionOperation("READ")
    public ResponseEntity<List<BankAccountDto>> list(
            @RequestParam(required = false) Long connectionId,
            @RequestParam(required = false) BankAccountType type) {
        List<BankAccountDto> out = new ArrayList<>();
        if (type == null || type == BankAccountType.BANK) {
            Map<Long, BankConnectionEntity> connections = connectionService.list().stream()
                    .collect(Collectors.toMap(BankConnectionEntity::getId, Function.identity(), (a, b) -> a));
            for (BankAccountEntity a : accountService.listBankAccounts(connectionId)) {
                Long cid = a.getConnection() == null ? null : a.getConnection().getId();
                out.add(BankAdminDtoMapper.bankAccount(a, cid == null ? null : connections.get(cid)));
            }
        }
        if ((type == null && connectionId == null) || type == BankAccountType.CASH) {
            cashLedgerService.listAccounts().stream().map(BankAdminDtoMapper::cashAccount).forEach(out::add);
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/accounts/{id}")
    @PermissionOperation("READ")
    public ResponseEntity<BankAccountDto> get(@PathVariable Long id) {
        BankAccountEntity account = accountService.get(id);
        if (account.getType() == BankAccountType.CASH) {
            return ResponseEntity.ok(BankAdminDtoMapper.cashAccount(cashLedgerService.getAccount(id)));
        }
        return ResponseEntity.ok(bankDto(account));
    }

    @PatchMapping("/accounts/{id}")
    @PermissionOperation("UPDATE")
    public ResponseEntity<BankAccountDto> update(
            @PathVariable Long id, @RequestBody(required = false) BankAdminRequests.UpdateAccount request) {
        if (request == null || request.includedInLedger() == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, INCLUDED_REQUIRED);
        }
        BankAccountEntity saved = accountService.setIncludedInLedger(id, request.includedInLedger(),
                request.holderOverrideReason());
        return ResponseEntity.ok(bankDto(saved));
    }

    // --- balance gaps (R7.3) -------------------------------------------------------------------

    @GetMapping("/accounts/{id}/balance-gaps")
    @PermissionOperation("READ")
    public ResponseEntity<List<BalanceGapDto>> balanceGaps(@PathVariable Long id) {
        List<BankBalanceGapEntity> gaps = accountService.listGaps(id);
        boolean hasGap = accountService.get(id).isHasBalanceGap();
        return ResponseEntity.ok(gaps.stream().map(g -> BankAdminDtoMapper.gap(g, id, hasGap)).toList());
    }

    @PostMapping("/balance-gaps/{id}/acknowledge")
    @PermissionOperation("UPDATE")
    public ResponseEntity<BalanceGapDto> acknowledgeGap(
            @PathVariable Long id, @RequestBody(required = false) BankAdminRequests.AcknowledgeGap request) {
        BankBalanceGapEntity gap = accountService.acknowledgeGap(id, request == null ? null : request.note());
        Long accountId = gap.getAccount() == null ? null : gap.getAccount().getId();
        boolean hasGap = accountId != null && accountService.get(accountId).isHasBalanceGap();
        return ResponseEntity.ok(BankAdminDtoMapper.gap(gap, accountId, hasGap));
    }

    // --- cash registers (R27.1) ----------------------------------------------------------------

    @GetMapping("/cash-accounts")
    @PermissionOperation("READ")
    public ResponseEntity<List<CashAccountDto>> listCash() {
        return ResponseEntity.ok(cashLedgerService.listAccounts());
    }

    @GetMapping("/cash-accounts/{id}")
    @PermissionOperation("READ")
    public ResponseEntity<CashAccountDto> getCash(@PathVariable Long id) {
        return ResponseEntity.ok(cashLedgerService.getAccount(id));
    }

    @PostMapping("/cash-accounts")
    @PermissionOperation("CREATE")
    public ResponseEntity<CashAccountDto> createCash(@RequestBody CashAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cashLedgerService.createAccount(request));
    }

    @PutMapping("/cash-accounts/{id}")
    @PermissionOperation("UPDATE")
    public ResponseEntity<CashAccountDto> updateCash(@PathVariable Long id, @RequestBody CashAccountRequest request) {
        return ResponseEntity.ok(cashLedgerService.updateAccount(id, request));
    }

    @PostMapping("/cash-accounts/{id}/archive")
    @PermissionOperation("UPDATE")
    public ResponseEntity<CashAccountDto> archiveCash(@PathVariable Long id) {
        return ResponseEntity.ok(cashLedgerService.archiveAccount(id));
    }

    // --- mapping -------------------------------------------------------------------------------

    private BankAccountDto bankDto(BankAccountEntity account) {
        Long cid = account.getConnection() == null ? null : account.getConnection().getId();
        BankConnectionEntity connection = cid == null ? null : connectionService.get(cid);
        return BankAdminDtoMapper.bankAccount(account, connection);
    }
}
