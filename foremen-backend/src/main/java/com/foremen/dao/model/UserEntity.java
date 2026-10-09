package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class UserEntity extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(length = 50)
    private String phone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private RoleEntity role;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private UserStatus status = UserStatus.INVITED;

    @Column(length = 5, nullable = false)
    private String locale = "ru";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> displayPreferences;

    /**
     * FOR-05-09 (R13.16, D8) — worker kind for a WORKER record created through the
     * Worker_Record_Flow. Nullable: every non-worker-record user keeps it {@code null},
     * which a WORKER view treats as {@link WorkerKind#PERSON}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "worker_kind")
    private WorkerKind workerKind;

    /** FOR-05-09 (R13.16, D8) — contact person; COMPANY worker records only, else empty. */
    @Column(name = "contact_person", length = 255)
    private String contactPerson;

    /**
     * FOR-05-09 (R13.5, R13.16, D8) — normalized 10-digit, checksum-validated NIP;
     * COMPANY worker records only, else empty.
     */
    @Column(name = "nip", length = 10)
    private String nip;
}
