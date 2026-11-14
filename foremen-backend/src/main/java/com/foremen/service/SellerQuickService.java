package com.foremen.service;

import com.foremen.dao.MaterialSellerDao;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.model.MaterialSellerServiceModel;
import com.foremen.service.model.mapper.MaterialSellerServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * FOR-11-00 (Requirements 20.1-20.5) — inline seller creation. A NIP hit returns the existing seller
 * ({@code created=false}); otherwise a seller is created with a generated code. Intentionally not
 * {@code @Transactional}: each DAO call commits on its own, so a unique-index violation from a
 * concurrent insert can be caught here and resolved by re-reading.
 */
@Service
@RequiredArgsConstructor
public class SellerQuickService {

    private static final int MAX_ATTEMPTS = 5;

    public record QuickResult(MaterialSellerServiceModel seller, boolean created) {}

    private final MaterialSellerDao dao;
    private final MaterialSellerServiceMapper mapper;

    public QuickResult findOrCreate(String rawName, String rawNip) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > 100) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.materialSeller.quick.nameRequired");
        }
        String nip = MaterialSellerService.normalizeNip(rawNip);

        if (nip != null) {
            Optional<MaterialSellerEntity> existing = dao.findByNip(nip);
            if (existing.isPresent()) {
                return new QuickResult(mapper.toServiceModel(existing.get()), false);
            }
        }

        DataIntegrityViolationException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            MaterialSellerEntity entity = new MaterialSellerEntity();
            entity.setCode(SellerCodeGenerator.generate(name, dao::existsByCode));
            entity.setNameRU(name);
            entity.setNamePL(name);
            entity.setActive(true);
            entity.setNip(nip);
            try {
                MaterialSellerEntity saved = dao.save(entity);
                return new QuickResult(mapper.toServiceModel(saved), true);
            } catch (DataIntegrityViolationException e) {
                last = e;
                if (nip != null) {
                    Optional<MaterialSellerEntity> winner = dao.findByNip(nip);
                    if (winner.isPresent()) {
                        return new QuickResult(mapper.toServiceModel(winner.get()), false);
                    }
                }
                // otherwise a concurrent code collision: regenerate the code and retry
            }
        }
        throw last;
    }
}
