package com.foremen.service.mail;

import com.foremen.dao.model.UserEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link InvitationEmailDispatcher}.
 *
 * <p>Verifies event-to-sender routing (client vs employee variant) and that a mail-transport
 * failure is swallowed so it never propagates after the issuing transaction has committed.
 */
class InvitationEmailDispatcherTest {

    private static final String INVITE_LINK = "http://localhost:3000/auth/set-password?token=abc";

    private final InvitationMailSender mailSender = Mockito.mock(InvitationMailSender.class);
    private final InvitationEmailDispatcher dispatcher = new InvitationEmailDispatcher(mailSender);

    @Test
    @DisplayName("Client event dispatches exactly one client-portal invitation and no set-password email")
    void clientEventSendsClientPortalInvitation() {
        UserEntity user = user("client@example.com");

        dispatcher.onInvitationEmail(new InvitationEmailEvent(user, true, null));

        verify(mailSender, times(1)).sendClientPortalInvitation(eq(user));
        verify(mailSender, never()).sendSetPasswordInvitation(any(), any());
    }

    @Test
    @DisplayName("Employee event dispatches exactly one set-password invitation carrying the link")
    void employeeEventSendsSetPasswordInvitationWithLink() {
        UserEntity user = user("employee@example.com");

        dispatcher.onInvitationEmail(new InvitationEmailEvent(user, false, INVITE_LINK));

        verify(mailSender, times(1)).sendSetPasswordInvitation(eq(user), eq(INVITE_LINK));
        verify(mailSender, never()).sendClientPortalInvitation(any());
    }

    @Test
    @DisplayName("A mail-transport failure is swallowed and does not propagate")
    void mailFailureIsSwallowed() {
        UserEntity user = user("employee@example.com");
        doThrow(new RuntimeException("SMTP down"))
                .when(mailSender).sendSetPasswordInvitation(any(), any());

        assertThatCode(() ->
                dispatcher.onInvitationEmail(new InvitationEmailEvent(user, false, INVITE_LINK)))
                .doesNotThrowAnyException();

        verify(mailSender, times(1)).sendSetPasswordInvitation(eq(user), eq(INVITE_LINK));
    }

    private static UserEntity user(String email) {
        UserEntity user = new UserEntity();
        user.setEmail(email);
        return user;
    }
}
