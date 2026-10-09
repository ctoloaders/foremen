package com.foremen.controller.model;

/**
 * 200 response payload of the {@code Worker_Invitation_Flow}
 * ({@code POST /api/users/worker/{id}/invite}, FOR-05-09 Requirement 13.17).
 *
 * <p>Echoes the invited worker's identity and the status it now reflects ("invited"). Carries no
 * password, token, or password-set link &mdash; the link is delivered only by the staff
 * password-set email (D-new).
 *
 * @param id     the invited WORKER user id
 * @param email  the stored email the invitation was sent to
 * @param status the user's status after the invite (reflecting "invited")
 */
public record WorkerInvitationResponse(
        Long id,
        String email,
        String status
) {}
