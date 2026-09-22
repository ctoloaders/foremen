package com.foremen.dao.model;

import com.foremen.dao.model.formula.FormulaAst;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A work item's optional default volume formula (FOR-05-04, Requirement 2): 0..1 row per
 * {@link WorkItemEntity}, carrying both the human-readable {@code sourceText} (for editing/audit,
 * R2.6) and the validated {@code parsedAst} (so repeated evaluation does not need to re-parse,
 * R2.6). Absence of a row for a work item means that work falls back to hand-entered
 * {@code EstimateLineRoomQty} (R2.5, R5.4).
 *
 * <p><b>Write-path contract (enforced at the service layer, not by this entity/JPA alone):</b>
 * {@code parsedAst} MUST always be derived from {@code sourceText} by running it through
 * {@code FormulaParser.parse(...)} and then {@code FormulaValidator.validate(...)} before persist
 * — never accepted directly from client input and never allowed to drift from {@code sourceText}.
 * This entity only carries the two already-validated fields; it does not itself invoke the parser
 * or validator (those are pure, stateless collaborators owned by the service/DAO layer, tasks
 * 15.x/18.3), matching the "provenance/derived field never trusted from the client" convention
 * already used for {@code WorkPriceServiceMapper}'s other FOR-05-04 derived fields.
 */
@Entity
@Table(name = "work_volume_formulas")
@Getter
@Setter
@NoArgsConstructor
public class WorkVolumeFormulaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_item_id", nullable = false, unique = true)
    private WorkItemEntity workItem;

    @Column(name = "source_text", nullable = false, columnDefinition = "text")
    private String sourceText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed_ast", columnDefinition = "jsonb", nullable = false)
    private FormulaAst parsedAst;
}
