package com.foremen.service.signing.merge;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.UserEntity;

/**
 * FOR-05-08 (Requirement 3.3): resolves the executor-representative placeholder group — the project
 * {@code MANAGER} / {@code FOREMAN} who represents the executor (design §Components resolver table
 * row {@code RepresentativeResolver}).
 *
 * <p>The business templates reference the representative as {@code {AssignedName}} /
 * {@code {AssignedLastName}} plus the documented Bitrix-CRM field-code prefix
 * {@code UfCrm1692271880…} for the representative's contacts. This resolver fixes that open prefix
 * to the concrete token keys below:
 *
 * <table>
 *   <caption>Supported tokens</caption>
 *   <tr><th>Token</th><th>Source ({@link MergeContext#representative()})</th></tr>
 *   <tr><td>{@code {AssignedName}}</td><td>{@link UserEntity#getName()}</td></tr>
 *   <tr><td>{@code {AssignedLastName}}</td><td>the surname part of the representative's name, if present</td></tr>
 *   <tr><td>{@code {UfCrm1692271880Email}}</td><td>{@link UserEntity#getEmail()}</td></tr>
 *   <tr><td>{@code {UfCrm1692271880Phone}}</td><td>{@link UserEntity#getPhone()}</td></tr>
 * </table>
 *
 * <p>Every token resolves to {@code null} (unresolved) when the representative is absent; {@code
 * {AssignedLastName}} is {@code null} unless the stored single {@code name} field actually contains
 * a surname token (Requirement 3.4, null→unresolved rather than fabricating a value).
 */
@Component
public class RepresentativeResolver implements MergeFieldResolver {

    static final String TOKEN_ASSIGNED_NAME = "AssignedName";
    static final String TOKEN_ASSIGNED_LAST_NAME = "AssignedLastName";

    /** The documented Bitrix-CRM field-code prefix for the representative's contacts (R3.3). */
    static final String CONTACT_PREFIX = "UfCrm1692271880";
    static final String TOKEN_EMAIL = CONTACT_PREFIX + "Email";
    static final String TOKEN_PHONE = CONTACT_PREFIX + "Phone";

    private static final Set<String> TOKENS = Set.of(
            TOKEN_ASSIGNED_NAME,
            TOKEN_ASSIGNED_LAST_NAME,
            TOKEN_EMAIL,
            TOKEN_PHONE);

    @Override
    public boolean supports(String token) {
        return TOKENS.contains(token);
    }

    @Override
    public String resolve(String token, MergeContext ctx) {
        UserEntity representative = ctx.representative();
        if (representative == null) {
            return null;
        }
        return switch (token) {
            case TOKEN_ASSIGNED_NAME -> blankToNull(representative.getName());
            case TOKEN_ASSIGNED_LAST_NAME -> lastName(representative.getName());
            case TOKEN_EMAIL -> blankToNull(representative.getEmail());
            case TOKEN_PHONE -> blankToNull(representative.getPhone());
            default -> null;
        };
    }

    /**
     * The surname part of a full name: the substring after the last space; {@code null} for a blank
     * or single-word name, so the token is reported unresolved rather than echoing the full name.
     */
    private static String lastName(String fullName) {
        if (fullName == null) {
            return null;
        }
        String trimmed = fullName.trim();
        int lastSpace = trimmed.lastIndexOf(' ');
        if (lastSpace < 0) {
            return null;
        }
        String surname = trimmed.substring(lastSpace + 1).trim();
        return surname.isEmpty() ? null : surname;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
