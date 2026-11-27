package com.foremen.service.estimateai;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.config.estimateai.EstimateAiProperties;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.estimateai.ai.EstimatePlanClient;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import com.foremen.service.roomsimport.EstimateAiErrors;

/**
 * FOR-05-04 (Requirements 2.1, 2.5, 2.8, 4.7) - the checks that must pass before an {@code ESTIMATE} job is
 * stored: the {@code ESTIMATE} UPDATE and {@code OFFERS} CREATE permissions on top of the {@code ROOMS} CREATE
 * of the endpoint ({@code 403}), the feature switch ({@code 503 error.estimateai.unavailable}), an unknown
 * {@code packageCode} ({@code 400}), a non-DRAFT estimate ({@code 409 error.estimate.locked}) and
 * {@code useExistingRooms} on a project without rooms ({@code 400 error.estimateai.no_rooms}). Nothing is
 * stored when a check fails.
 */
@Component
public class EstimateAiStartGuard {

    static final String ESTIMATE_RESOURCE = "ESTIMATE";
    static final String OFFERS_RESOURCE = "OFFERS";

    private final ForemenPermissionEvaluator permissionEvaluator;
    private final EstimateAiProperties properties;
    private final EstimatePlanClient planClient;
    private final EstimateDao estimateDao;
    private final RoomDao roomDao;
    private final OfferPackageDao packageDao;

    public EstimateAiStartGuard(ForemenPermissionEvaluator permissionEvaluator, EstimateAiProperties properties,
                                EstimatePlanClient planClient, EstimateDao estimateDao, RoomDao roomDao,
                                OfferPackageDao packageDao) {
        this.permissionEvaluator = permissionEvaluator;
        this.properties = properties;
        this.planClient = planClient;
        this.estimateDao = estimateDao;
        this.roomDao = roomDao;
        this.packageDao = packageDao;
    }

    /**
     * @throws ForemenApiException 403 / 503 / 400 / 409 as described on the class
     */
    @Transactional(readOnly = true)
    public void assertCanStart(Long projectId, boolean useExistingRooms, String packageCode) {
        String role = currentRoleCode();
        if (role == null || !permissionEvaluator.isAllowed(role, ESTIMATE_RESOURCE, "UPDATE")
                || !permissionEvaluator.isAllowed(role, OFFERS_RESOURCE, "CREATE")) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied");
        }
        if (!properties.enabled() || !planClient.isEnabled()) {
            throw new ForemenApiException(HttpStatus.SERVICE_UNAVAILABLE, EstimateAiErrors.UNAVAILABLE);
        }
        if (packageCode != null && !packageCode.isBlank() && packageDao.findByCode(packageCode.strip()).isEmpty()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.entity.not.found")
                    .withParams(Map.of("packageCode", packageCode));
        }
        estimateDao.findByProjectId(projectId)
                .filter(e -> e.getStatus() != EstimateStatus.DRAFT)
                .ifPresent(e -> {
                    throw new ForemenApiException(HttpStatus.CONFLICT, EstimateAiErrors.ESTIMATE_LOCKED);
                });
        if (useExistingRooms && roomDao.findByProjectId(projectId).isEmpty()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, EstimateAiErrors.NO_ROOMS_START);
        }
    }

    private static String currentRoleCode() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && authority.startsWith("ROLE_")) {
                return authority.substring("ROLE_".length());
            }
        }
        return null;
    }
}
