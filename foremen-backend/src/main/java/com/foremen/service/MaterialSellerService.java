package com.foremen.service;

import com.foremen.dao.MaterialSellerDao;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.MaterialSellerServiceExtendedModel;
import com.foremen.service.model.MaterialSellerServiceModel;
import com.foremen.service.model.mapper.MaterialSellerServiceMapper;
import com.foremen.exception.ForemenApiException;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class MaterialSellerService implements AdminService<
        MaterialSellerServiceModel, MaterialSellerServiceExtendedModel, MaterialSellerEntity, Long> {

    private final MaterialSellerDao dao;
    private final MaterialSellerServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<MaterialSellerEntity> daoModelClass = MaterialSellerEntity.class;

    /**
     * Normalizes a submitted NIP to ten digits; blank/null yields {@code null}. An invalid value is
     * rejected with 400 {@code error.materialSeller.nip.invalid}.
     */
    public static String normalizeNip(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!NipValidator.isValidNip(raw.trim())) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.materialSeller.nip.invalid");
        }
        return NipValidator.normalize(raw.trim());
    }

    @Override
    public void validateCreate(MaterialSellerServiceExtendedModel model) {
        assertNipUnique(normalizeNip(model.nip()), null);
    }

    @Override
    public void validateUpdate(MaterialSellerEntity existing, MaterialSellerServiceExtendedModel update) {
        assertNipUnique(normalizeNip(update.nip()), existing.getId());
    }

    private void assertNipUnique(String nip, Long selfId) {
        if (nip == null) {
            return;
        }
        dao.findByNip(nip).ifPresent(other -> {
            if (!other.getId().equals(selfId)) {
                throw new ForemenApiException(HttpStatus.CONFLICT, "error.materialSeller.nip.duplicate");
            }
        });
    }
}
