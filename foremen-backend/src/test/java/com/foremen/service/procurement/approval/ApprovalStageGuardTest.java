package com.foremen.service.procurement.approval;

import java.util.EnumSet;
import java.util.Set;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.approval.ApprovalStageGuard.Result;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

import static com.foremen.dao.model.PositionStatus.*;
import static com.foremen.service.procurement.approval.ApprovalOperation.*;
import static org.assertj.core.api.Assertions.assertThat;

/** <b>Validates: Requirements 14.4</b> */
class ApprovalStageGuardTest {

    private static Set<PositionStatus> expected(SourcingPath path, ApprovalOperation op) {
        Set<PositionStatus> none = EnumSet.noneOf(PositionStatus.class);
        return switch (op) {
            case SEND -> path == SourcingPath.AGREED ? EnumSet.of(PLANNED) : none;
            case SEND_CHOICE -> path == SourcingPath.PACKAGE ? EnumSet.of(PLANNED) : none;
            case PROPOSE_ANALOG, SUGGEST, CLIENT_OWN ->
                    path == SourcingPath.AGREED ? EnumSet.of(PROPOSED, ACCEPTED) : none;
            case ACCEPT, REJECT -> path == SourcingPath.AGREED ? EnumSet.of(PROPOSED)
                    : path == SourcingPath.PACKAGE ? EnumSet.of(PLANNED) : none;
        };
    }

    @Test
    void everyPathStageOperationTriple() {
        for (SourcingPath path : SourcingPath.values()) {
            for (PositionStatus status : PositionStatus.values()) {
                for (ApprovalOperation op : ApprovalOperation.values()) {
                    Result expectedResult = expected(path, op).contains(status) ? Result.OK : Result.STAGE_NOT_ALLOWED;
                    assertThat(ApprovalStageGuard.check(path, status, op))
                            .as("%s %s %s", path, status, op).isEqualTo(expectedResult);
                }
            }
        }
    }

    @Test
    void spotChecksFromTheDesignTable() {
        assertThat(ApprovalStageGuard.check(SourcingPath.AGREED, PLANNED, SEND)).isEqualTo(Result.OK);
        assertThat(ApprovalStageGuard.check(SourcingPath.AGREED, ACCEPTED, PROPOSE_ANALOG)).isEqualTo(Result.OK);
        assertThat(ApprovalStageGuard.check(SourcingPath.PACKAGE, PLANNED, ACCEPT)).isEqualTo(Result.OK);
        assertThat(ApprovalStageGuard.check(SourcingPath.AGREED, IN_PROFORMA, PROPOSE_ANALOG))
                .isEqualTo(Result.STAGE_NOT_ALLOWED);
        assertThat(ApprovalStageGuard.check(null, PLANNED, SEND)).isEqualTo(Result.STAGE_NOT_ALLOWED);
    }

    /** From IN_PROFORMA on (including CANCELLED) nothing is allowed, for any path and operation. */
    @Property
    void fromInProformaOnNothingIsAllowed(@ForAll SourcingPath path, @ForAll PositionStatus status,
                                          @ForAll ApprovalOperation op) {
        if (status.ordinal() >= IN_PROFORMA.ordinal()) {
            assertThat(ApprovalStageGuard.check(path, status, op)).isEqualTo(Result.STAGE_NOT_ALLOWED);
        }
    }
}
