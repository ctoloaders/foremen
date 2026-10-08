package com.foremen.service.signing.merge;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.UserEntity;

/**
 * FOR-05-08 (Requirement 3.3): resolves the client ({@code Zamawiający} / {@code Klient})
 * placeholder group from the project's primary {@code CLIENT} member (design §Components resolver
 * table row {@code ClientResolver}).
 *
 * <table>
 *   <caption>Supported tokens</caption>
 *   <tr><th>Token</th><th>Source</th></tr>
 *   <tr><td>{@code {ContactName}}</td><td>{@link UserEntity#getName()} of the primary CLIENT member</td></tr>
 *   <tr><td>{@code {ContactLastName}}</td><td>the surname part of the CLIENT member's name, if the name has one</td></tr>
 *   <tr><td>{@code {ContactEmail}}</td><td>{@link UserEntity#getEmail()}</td></tr>
 *   <tr><td>{@code {ContactAddress}}</td><td>the project address (the client's contact address of record)</td></tr>
 *   <tr><td>{@code {RequisitePrimaryAddressText}}</td><td>the project address (the client's primary requisite address)</td></tr>
 * </table>
 *
 * <p>All tokens resolve against {@link MergeContext#primaryClient()}; when the project has no CLIENT
 * member every token is unresolved ({@code null}). {@code {ContactLastName}} is {@code null} unless
 * the stored name actually contains a surname token — the platform keeps a single {@code name}
 * field, so a one-word name has no resolvable last name (Requirement 3.4, null→unresolved rather
 * than fabricating an empty surname).
 */
@Component
public class ClientResolver implements MergeFieldResolver {

    static final String TOKEN_CONTACT_NAME = "ContactName";
    static final String TOKEN_CONTACT_LAST_NAME = "ContactLastName";
    static final String TOKEN_CONTACT_EMAIL = "ContactEmail";
    static final String TOKEN_CONTACT_ADDRESS = "ContactAddress";
    static final String TOKEN_REQUISITE_PRIMARY_ADDRESS = "RequisitePrimaryAddressText";

    private static final Set<String> TOKENS = Set.of(
            TOKEN_CONTACT_NAME,
            TOKEN_CONTACT_LAST_NAME,
            TOKEN_CONTACT_EMAIL,
            TOKEN_CONTACT_ADDRESS,
            TOKEN_REQUISITE_PRIMARY_ADDRESS);

    @Override
    public boolean supports(String token) {
        return TOKENS.contains(token);
    }

    @Override
    public String resolve(String token, MergeContext ctx) {
        UserEntity client = ctx.primaryClient();
        return switch (token) {
            case TOKEN_CONTACT_NAME -> client == null ? null : blankToNull(client.getName());
            case TOKEN_CONTACT_LAST_NAME -> client == null ? null : lastName(client.getName());
            case TOKEN_CONTACT_EMAIL -> client == null ? null : blankToNull(client.getEmail());
            case TOKEN_CONTACT_ADDRESS, TOKEN_REQUISITE_PRIMARY_ADDRESS -> projectAddress(ctx);
            default -> null;
        };
    }

    /**
     * The surname part of a full name: the substring after the last space. Returns {@code null} when
     * the name is blank or has no surname token (a single-word name), so the token is reported
     * unresolved rather than echoing the full name as a "last name".
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

    private static String projectAddress(MergeContext ctx) {
        ProjectEntity project = ctx.project();
        if (project == null) {
            return null;
        }
        String formatted = blankToNull(project.getFormattedAddress());
        return formatted != null ? formatted : blankToNull(project.getAddress());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
