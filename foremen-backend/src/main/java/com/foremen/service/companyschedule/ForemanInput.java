package com.foremen.service.companyschedule;

/**
 * One project's foreman-span source (FOR-07-01): an ACTIVE FOREMAN member, or — FOR-07-01 redesign,
 * user decisions 5b/6d — an ACTIVE MANAGER member promoted to a foreman-style span because the project
 * has no ACTIVE foreman ({@code managerActingAsForeman = true}; the UI emphasizes such spans).
 *
 * @param userId                 the user id
 * @param userName               the user's display name
 * @param managerActingAsForeman whether this span is a manager standing in for a missing foreman
 */
public record ForemanInput(long userId, String userName, boolean managerActingAsForeman) {

    /** A real FOREMAN member's span. */
    public ForemanInput(long userId, String userName) {
        this(userId, userName, false);
    }
}
