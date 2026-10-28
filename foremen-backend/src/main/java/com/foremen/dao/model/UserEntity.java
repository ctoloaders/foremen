package com.foremen.dao.model;

import com.foremen.config.persistence.LocaleAttributeConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /**
     * FOR-02-07 (users UI, worker-type bugfix) — OPTIONAL worker-type (cost tier) for a WORKER user.
     * Nullable: non-WORKER users and WORKER users saved without a selection keep it {@code null}.
     * The service resolves/persists it only when the user's role code is {@code WORKER}
     * (D-worker-only), mirroring {@link #workCategories}. FK is {@code ON DELETE SET NULL}
     * (changeset 188) so removing a worker-type dictionary row drops the user back to no type.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "worker_type_id")
    private WorkerTypeEntity workerType;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private UserStatus status = UserStatus.INVITED;

    /**
     * FOR-06-12 (R12.3, R12.5) — the user's preferred locale, migrated {@code String → Locale}
     * backwards-compatibly: the {@code users.locale} {@code VARCHAR} column keeps its existing
     * lowercase {@code "pl"}/{@code "ru"} values, round-tripped unchanged by
     * {@link LocaleAttributeConverter} (no DB migration). A {@code null}/blank/unsupported stored
     * value resolves to PL on read. The default is RU, preserving the previous {@code "ru"} default.
     */
    @Convert(converter = LocaleAttributeConverter.class)
    @Column(length = 5, nullable = false)
    private Locale locale = LocaleAttributeConverter.RU;

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
