package com.foremen.dao.model;

import com.foremen.service.procurement.approval.ApprovalRoundKind;
import com.foremen.service.procurement.approval.ApprovalRoundStatus;
import com.foremen.service.procurement.approval.DecidedSide;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** FOR-11-02 (R2.1): one approval round of a purchase position ({@code position_approval_rounds}). */
@Entity
@Table(name = "position_approval_rounds")
@Getter
@Setter
@NoArgsConstructor
public class PositionApprovalRoundEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    @JoinColumn(name = "position_id", nullable = false)
    private PurchasePositionEntity position;

    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ApprovalRoundKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ApprovalRoundStatus status;

    @Column(name = "decision_deadline")
    private LocalDate decisionDeadline;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 24)
    private PositionStatus previousStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "base_material_id")
    private FinishingMaterialEntity baseMaterial;

    @Column(name = "base_unit_sale_price", precision = 14, scale = 2)
    private BigDecimal baseUnitSalePrice;

    @Column(precision = 14, scale = 3)
    private BigDecimal quantity;

    @Column(name = "manager_note", length = 1000)
    private String managerNote;

    @Column(name = "suggestion_link", length = 1000)
    private String suggestionLink;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_suggestion_id")
    private PositionApprovalRoundEntity sourceSuggestion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_id")
    private UserEntity decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "decided_side", length = 8)
    private DecidedSide decidedSide;

    @Column(name = "on_behalf", nullable = false)
    private boolean onBehalf;

    @Column(name = "decision_comment", length = 1000)
    private String decisionComment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chosen_option_id")
    private PositionApprovalOptionEntity chosenOption;
}
