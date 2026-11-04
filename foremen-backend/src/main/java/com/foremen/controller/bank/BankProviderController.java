package com.foremen.controller.bank;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.bank.dto.BankProviderDto;
import com.foremen.controller.bank.dto.SupportedBanksResponse;
import com.foremen.dao.model.FeedProvider;
import com.foremen.service.bank.account.SupportedBanksService;
import com.foremen.service.bank.account.SupportedBanksService.SupportedBanks;
import com.foremen.service.bank.connector.BankFeedConnector;
import com.foremen.service.bank.connector.BankFeedConnectorRegistry;
import com.foremen.service.bank.connector.CredentialField;
import com.foremen.service.bank.connector.ProviderBank;
import com.foremen.service.bank.connector.saltedge.SaltEdgeConnector;

import lombok.RequiredArgsConstructor;

/**
 * FOR-07-04 (Requirements 2.2, 4.5, 9.2, 19.4, 19.9, 21.3) — bank-feed providers and their live
 * supported-bank catalogue.
 *
 * <ul>
 *   <li>{@code GET /api/bank/providers} → the <b>available</b> providers (enum order) with the Salt
 *       Edge mode and the per-connection credential field descriptors ({@code BANK_CONNECTIONS} READ);</li>
 *   <li>{@code GET /api/bank/providers/{provider}/banks?country=PL&q=&businessOnly=&includeTest=&refresh=}
 *       → the supported banks after the tab filters, with {@code fetchedAt} and {@code emptyReason}
 *       ({@code BANK_CONNECTIONS} READ). An unavailable provider → {@code 409
 *       error.bank.provider.notAvailable}; a provider failure → {@code error.bank.provider.*}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bank/providers")
@RequiredArgsConstructor
@PermissionResource("BANK_CONNECTIONS")
public class BankProviderController {

    private final BankFeedConnectorRegistry registry;
    private final SupportedBanksService supportedBanksService;

    @GetMapping
    @PermissionOperation("READ")
    public ResponseEntity<List<BankProviderDto>> providers() {
        List<BankProviderDto> providers = registry.available().stream()
                .map(p -> registry.find(p).map(BankProviderController::toDto).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        return ResponseEntity.ok(providers);
    }

    /**
     * @param includeTest keep fake / sandbox banks (default {@code true}: the connector already hides
     *                    fake providers when they are not enabled for the environment)
     * @param refresh     bypass the 1-hour catalogue cache
     */
    @GetMapping("/{provider}/banks")
    @PermissionOperation("READ")
    public ResponseEntity<SupportedBanksResponse> banks(
            @PathVariable FeedProvider provider,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean businessOnly,
            @RequestParam(defaultValue = "true") boolean includeTest,
            @RequestParam(defaultValue = "false") boolean refresh) {
        SupportedBanks catalogue = supportedBanksService.list(provider, country, refresh);
        List<ProviderBank> filtered = SupportedBanksService.filter(catalogue.banks(), q, businessOnly, includeTest);
        return ResponseEntity.ok(new SupportedBanksResponse(catalogue.provider(), catalogue.mode(),
                catalogue.country(), filtered, catalogue.banks().size(), catalogue.fetchedAt(),
                catalogue.emptyReason()));
    }

    static BankProviderDto toDto(BankFeedConnector connector) {
        List<CredentialField> fields = connector.credentialFields() == null ? List.of() : connector.credentialFields();
        return new BankProviderDto(
                connector.provider(),
                connector instanceof SaltEdgeConnector saltEdge ? saltEdge.mode() : null,
                connector.isAvailable(),
                !fields.isEmpty(),
                List.copyOf(fields));
    }
}
