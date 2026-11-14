package com.foremen.service;

import com.foremen.dao.MaterialSellerDao;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.model.MaterialSellerServiceModel;
import com.foremen.service.model.mapper.MaterialSellerServiceMapper;
import com.foremen.service.nip.NipLookupResult;
import com.foremen.service.nip.NipLookupService;
import com.foremen.service.nip.NipLookupUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * FOR-11-00 (Requirements 5.1-5.8, 4.7) — resolves a seller by NIP: dictionary first, then the NIP
 * registry SPI. Read-only: a registry candidate is never persisted.
 */
@Service
@RequiredArgsConstructor
public class SellerLookupService {

    public enum Source { DICTIONARY, REGISTRY, NONE }

    public record SellerLookupResult(String nip, Source source, MaterialSellerServiceModel seller,
                                     NipLookupResult candidate, boolean registryAvailable) {}

    private final MaterialSellerDao dao;
    private final MaterialSellerServiceMapper mapper;
    private final NipLookupService nipLookupService;

    @Transactional(readOnly = true)
    public SellerLookupResult lookup(String rawNip) {
        String nip = MaterialSellerService.normalizeNip(rawNip);
        if (nip == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.materialSeller.nip.invalid");
        }
        boolean available = nipLookupService.isConfigured();

        var existing = dao.findByNip(nip);
        if (existing.isPresent()) {
            return new SellerLookupResult(nip, Source.DICTIONARY,
                    mapper.toServiceModel(existing.get()), null, available);
        }
        if (!available) {
            return new SellerLookupResult(nip, Source.NONE, null, null, false);
        }
        Optional<NipLookupResult> found;
        try {
            found = nipLookupService.lookup(nip);
        } catch (NipLookupUnavailableException e) {
            throw new ForemenApiException(HttpStatus.BAD_GATEWAY, "error.nipLookup.unavailable");
        }
        return found
                .map(c -> new SellerLookupResult(nip, Source.REGISTRY, null, c, true))
                .orElseGet(() -> new SellerLookupResult(nip, Source.NONE, null, null, true));
    }
}
