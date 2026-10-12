package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * FOR-05-10b (R1.1, D-model) — the ordered DEFAULT list of Work_Categories a WORKER user can
     * perform (User_Work_Categories). Mapped from the {@code user_work_categories} FK join table
     * (changeset 154); {@code @OrderColumn} preserves the submitted order, {@code ON DELETE CASCADE}
     * discards the links with the user. Applies to WORKER users only (D-worker-only); non-WORKER
     * users keep it empty. The referenced category FK is {@code ON DELETE RESTRICT} at the DB level.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_work_categories",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "work_category_id"))
    @OrderColumn(name = "order_no")
    private List<WorkCategoryEntity> workCategories = new ArrayList<>();
}
