package com.foremen.service.signing.merge;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.CompanyProfileEntity;

/**
 * FOR-05-08 (Requirement 3.3): resolves the executor ({@code Wykonawca}) requisites placeholder
 * group from the single admin-managed {@link CompanyProfileEntity} (design §Components resolver table
 * row {@code CompanyRequisitesResolver}; design key decision 7 / Open Question 1).
 *
 * <p>The business templates use Bitrix-CRM-style field codes under the documented
 * {@code UfCrm1663076108Requisite…} prefix for the executor requisites. This resolver fixes that
 * open prefix to the concrete, documented token keys below (name, registered address, NIP, REGON,
 * representative name/role, email):
 *
 * <table>
 *   <caption>Supported tokens</caption>
 *   <tr><th>Token</th><th>Source ({@link CompanyProfileEntity})</th></tr>
 *   <tr><td>{@code {UfCrm1663076108RequisiteName}}</td><td>{@link CompanyProfileEntity#getFullName()}</td></tr>
 *   <tr><td>{@code {UfCrm1663076108RequisiteAddress}}</td><td>{@link CompanyProfileEntity#getRegisteredAddress()}</td></tr>
 *   <tr><td>{@code {UfCrm1663076108RequisiteNip}}</td><td>{@link CompanyProfileEntity#getNip()}</td></tr>
 *   <tr><td>{@code {UfCrm1663076108RequisiteRegon}}</td><td>{@link CompanyProfileEntity#getRegon()}</td></tr>
 *   <tr><td>{@code {UfCrm1663076108RequisiteRepresentativeName}}</td><td>{@link CompanyProfileEntity#getRepresentativeName()}</td></tr>
 *   <tr><td>{@code {UfCrm1663076108RequisiteRepresentativeRole}}</td><td>{@link CompanyProfileEntity#getRepresentativeRole()}</td></tr>
 *   <tr><td>{@code {UfCrm1663076108RequisiteEmail}}</td><td>{@link CompanyProfileEntity#getEmail()}</td></tr>
 * </table>
 *
 * <p>Every token resolves to {@code null} (unresolved) when the {@code CompanyProfile} is absent or
 * the respective requisite field is blank — the optional requisites ({@code regon}, representative
 * name/role, email) are routinely absent and must surface as unresolved rather than empty text
 * (Requirement 3.4, null→unresolved).
 */
@Component
public class CompanyRequisitesResolver implements MergeFieldResolver {

    /** The documented Bitrix-CRM field-code prefix for the executor requisites group (R3.3). */
    static final String PREFIX = "UfCrm1663076108Requisite";

    static final String TOKEN_NAME = PREFIX + "Name";
    static final String TOKEN_ADDRESS = PREFIX + "Address";
    static final String TOKEN_NIP = PREFIX + "Nip";
    static final String TOKEN_REGON = PREFIX + "Regon";
    static final String TOKEN_REPRESENTATIVE_NAME = PREFIX + "RepresentativeName";
    static final String TOKEN_REPRESENTATIVE_ROLE = PREFIX + "RepresentativeRole";
    static final String TOKEN_EMAIL = PREFIX + "Email";

    private static final Set<String> TOKENS = Set.of(
            TOKEN_NAME,
            TOKEN_ADDRESS,
            TOKEN_NIP,
            TOKEN_REGON,
            TOKEN_REPRESENTATIVE_NAME,
            TOKEN_REPRESENTATIVE_ROLE,
            TOKEN_EMAIL);

    @Override
    public boolean supports(String token) {
        return TOKENS.contains(token);
    }

    @Override
    public String resolve(String token, MergeContext ctx) {
        CompanyProfileEntity company = ctx.companyProfile();
        if (company == null) {
            return null;
        }
        return switch (token) {
            case TOKEN_NAME -> blankToNull(company.getFullName());
            case TOKEN_ADDRESS -> blankToNull(company.getRegisteredAddress());
            case TOKEN_NIP -> blankToNull(company.getNip());
            case TOKEN_REGON -> blankToNull(company.getRegon());
            case TOKEN_REPRESENTATIVE_NAME -> blankToNull(company.getRepresentativeName());
            case TOKEN_REPRESENTATIVE_ROLE -> blankToNull(company.getRepresentativeRole());
            case TOKEN_EMAIL -> blankToNull(company.getEmail());
            default -> null;
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
