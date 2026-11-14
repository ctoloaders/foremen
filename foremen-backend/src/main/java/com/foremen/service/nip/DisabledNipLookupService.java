package com.foremen.service.nip;

import com.foremen.config.nip.NipLookupNotConfiguredCondition;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * FOR-11-00 (Requirements 6.2, 6.6) — fallback {@link NipLookupService} wired when no registry is
 * configured. Always returns {@link Optional#empty()}.
 */
@Service
@Conditional(NipLookupNotConfiguredCondition.class)
public class DisabledNipLookupService implements NipLookupService {

    @Override
    public Optional<NipLookupResult> lookup(String nip) {
        return Optional.empty();
    }

    @Override
    public boolean isConfigured() {
        return false;
    }
}
