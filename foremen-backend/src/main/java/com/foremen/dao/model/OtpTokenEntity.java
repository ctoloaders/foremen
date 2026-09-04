package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "otp_tokens", indexes = @Index(name = "idx_otp_tokens_email", columnList = "email"))
@Getter
@Setter
@NoArgsConstructor
public class OtpTokenEntity extends BaseEntity {

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "code", nullable = false, length = 6)
    private String code;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used", nullable = false)
    private boolean used = false;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;
}
