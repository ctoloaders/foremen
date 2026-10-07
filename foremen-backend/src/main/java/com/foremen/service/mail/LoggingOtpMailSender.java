package com.foremen.service.mail;

import com.foremen.dao.model.UserEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * OTP mail sender used under the `docker` profile (local/QA stack) where no real SMTP is
 * configured. It never dispatches a real email and never throws, so OtpService.request() commits
 * and the otp_tokens row persists (the QA E2E suite reads the code from the DB). Marked @Primary so
 * it wins over {@link ThymeleafOtpMailSender} when the `docker` profile is active; under any other
 * profile this bean is absent and the Thymeleaf/SMTP sender remains the only OtpMailSender.
 */
@Slf4j
@Component
@Profile("docker")
@Primary
public class LoggingOtpMailSender implements OtpMailSender {
    @Override
    public void send(UserEntity user, String code) {
        log.info("[docker profile] OTP email suppressed; code for {} is available via otp_tokens", user.getEmail());
    }
}
