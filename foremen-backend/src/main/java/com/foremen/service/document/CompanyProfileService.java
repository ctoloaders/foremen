package com.foremen.service.document;

import java.util.Iterator;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.CompanyProfileDao;
import com.foremen.dao.model.CompanyProfileEntity;
import com.foremen.exception.ForemenApiException;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-08 (Requirement 3.3 / design decision 7, Open Question 1; design §Data Models
 * {@code CompanyProfileEntity}): the ADMIN-only, GLOBAL (not project-scoped) requisites surface over
 * the single {@link CompanyProfileEntity} row — the executor ({@code Wykonawca}) requisites that
 * {@code CompanyRequisitesResolver} reads when merging a document body.
 *
 * <h2>Single-row invariant</h2>
 * The "single-row" intent is enforced here at the service layer (the schema is a plain table): there
 * is no create endpoint — {@link #save(CompanyProfileInput)} upserts the one row (creating it on
 * first save, overwriting it thereafter) and {@link #get()} reads it. The resolver and the admin UI
 * only ever see the one row.
 */
@Service
@RequiredArgsConstructor
public class CompanyProfileService {

    /** 400 when a save supplies no (fullName, registeredAddress, nip). */
    static final String PROFILE_INVALID_MESSAGE = "error.company.profile.invalid";

    private final CompanyProfileDao companyProfileDao;

    /**
     * Reads the single company profile, if one has been saved (R3.3). Returns an empty DTO (all
     * fields null) when no profile exists yet, so the admin screen renders an empty form rather than
     * failing.
     *
     * @return the profile read DTO (empty-valued when none is saved)
     */
    @Transactional(readOnly = true)
    public CompanyProfileDto get() {
        CompanyProfileEntity existing = findSingle();
        return existing == null ? empty() : toDto(existing);
    }

    /**
     * Upserts the single company profile (R3.3): creates the one row on first save, overwrites it on
     * every subsequent save. {@code fullName}, {@code registeredAddress} and {@code nip} are required.
     *
     * @param in the requisites payload
     * @return the saved profile read DTO
     * @throws ForemenApiException 400 on an invalid payload
     */
    @Transactional
    public CompanyProfileDto save(CompanyProfileInput in) {
        validate(in);
        CompanyProfileEntity profile = findSingle();
        if (profile == null) {
            profile = new CompanyProfileEntity();
        }
        profile.setFullName(in.fullName());
        profile.setRegisteredAddress(in.registeredAddress());
        profile.setNip(in.nip());
        profile.setRegon(in.regon());
        profile.setRepresentativeName(in.representativeName());
        profile.setRepresentativeRole(in.representativeRole());
        profile.setEmail(in.email());
        return toDto(companyProfileDao.save(profile));
    }

    // --- internals ---

    /** The single company-profile row, or {@code null} when none has been saved yet. */
    private CompanyProfileEntity findSingle() {
        Iterator<CompanyProfileEntity> it = companyProfileDao.findAll().iterator();
        return it.hasNext() ? it.next() : null;
    }

    private void validate(CompanyProfileInput in) {
        if (in == null || isBlank(in.fullName()) || isBlank(in.registeredAddress()) || isBlank(in.nip())) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, PROFILE_INVALID_MESSAGE);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static CompanyProfileDto empty() {
        return new CompanyProfileDto(null, null, null, null, null, null, null, null);
    }

    private static CompanyProfileDto toDto(CompanyProfileEntity profile) {
        return new CompanyProfileDto(
                profile.getId(),
                profile.getFullName(),
                profile.getRegisteredAddress(),
                profile.getNip(),
                profile.getRegon(),
                profile.getRepresentativeName(),
                profile.getRepresentativeRole(),
                profile.getEmail());
    }
}
