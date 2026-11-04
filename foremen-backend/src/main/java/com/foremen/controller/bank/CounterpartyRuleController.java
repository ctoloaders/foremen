package com.foremen.controller.bank;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.service.bank.link.CounterpartyRuleDto;
import com.foremen.service.bank.link.CounterpartyRuleRequest;
import com.foremen.service.bank.link.CounterpartyRuleService;

import lombok.RequiredArgsConstructor;

/**
 * FOR-07-04 (Requirements 17.4, 19.7, 21.1, 21.3) — counterparty rules CRUD
 * ({@code BANK_CONNECTIONS}): {@code GET /api/bank/counterparty-rules} (READ, pageable),
 * {@code GET /{id}} (READ), {@code POST} (CREATE → 201), {@code PUT /{id}} (UPDATE),
 * {@code DELETE /{id}} (DELETE → 204). Validation codes ({@code error.bank.rule.*}) come from
 * {@link CounterpartyRuleService}.
 */
@RestController
@RequestMapping("/api/bank/counterparty-rules")
@RequiredArgsConstructor
@PermissionResource("BANK_CONNECTIONS")
public class CounterpartyRuleController {

    private final CounterpartyRuleService ruleService;

    @GetMapping
    @PermissionOperation("READ")
    public ResponseEntity<Page<CounterpartyRuleDto>> list(
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ruleService.list(pageable));
    }

    @GetMapping("/{id}")
    @PermissionOperation("READ")
    public ResponseEntity<CounterpartyRuleDto> get(@PathVariable Long id) {
        return ResponseEntity.ok(ruleService.get(id));
    }

    @PostMapping
    @PermissionOperation("CREATE")
    public ResponseEntity<CounterpartyRuleDto> create(@RequestBody CounterpartyRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ruleService.create(request));
    }

    @PutMapping("/{id}")
    @PermissionOperation("UPDATE")
    public ResponseEntity<CounterpartyRuleDto> update(@PathVariable Long id, @RequestBody CounterpartyRuleRequest request) {
        return ResponseEntity.ok(ruleService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PermissionOperation("DELETE")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        ruleService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
