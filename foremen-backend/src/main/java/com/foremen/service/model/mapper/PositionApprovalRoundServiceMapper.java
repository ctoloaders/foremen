package com.foremen.service.model.mapper;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.PositionApprovalRoundEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.PositionApprovalRoundServiceModel;

/**
 * FOR-11-02: plumbing mapper for {@code PositionApprovalService}. Rounds are written only through the
 * service's bespoke operations, so the generic write mappings fail loudly.
 */
@Component
public class PositionApprovalRoundServiceMapper
        implements ServiceToDaoMapper<PositionApprovalRoundEntity, PositionApprovalRoundServiceModel,
        PositionApprovalRoundServiceModel> {

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of();
    }

    @Override
    public PositionApprovalRoundServiceModel toServiceModel(PositionApprovalRoundEntity source) {
        if (source == null) {
            return null;
        }
        PositionApprovalRoundServiceModel m = new PositionApprovalRoundServiceModel();
        m.setId(source.getId());
        m.setPositionId(source.getPosition() == null ? null : source.getPosition().getId());
        m.setRoundNo(source.getRoundNo());
        m.setKind(source.getKind());
        m.setStatus(source.getStatus());
        m.setDecisionDeadline(source.getDecisionDeadline());
        return m;
    }

    @Override
    public PositionApprovalRoundServiceModel toServiceExtendedModel(PositionApprovalRoundEntity source) {
        return toServiceModel(source);
    }

    @Override
    public PositionApprovalRoundEntity toCreateDaoModel(PositionApprovalRoundServiceModel source) {
        throw new UnsupportedOperationException("Approval rounds are created only by PositionApprovalService.");
    }

    @Override
    public void updateFields(PositionApprovalRoundServiceModel source, PositionApprovalRoundEntity target) {
        throw new UnsupportedOperationException("Approval rounds are changed only by PositionApprovalService.");
    }
}
