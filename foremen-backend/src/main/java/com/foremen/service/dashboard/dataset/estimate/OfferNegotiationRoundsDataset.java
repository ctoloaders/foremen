package com.foremen.service.dashboard.dataset.estimate;

import com.foremen.dao.model.DiscountKind;
import com.foremen.dao.model.DiscountScope;
import com.foremen.dao.model.NegotiationRoundKind;
import com.foremen.dao.model.NegotiationRoundStatus;
import com.foremen.dao.model.OfferNegotiationRoundEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.foremen.service.dashboard.dataset.estimate.EstimateDatasetSupport.*;

/** Offer negotiation rounds. Free-text justification / explanation / client comment are NOT exposed. */
@Component
public class OfferNegotiationRoundsDataset extends AbstractEstimateDataset {

    public OfferNegotiationRoundsDataset() {
        super("OFFER_NEGOTIATION_ROUNDS", "OFFERS", OfferNegotiationRoundEntity.class,
                "offer.project.id", "createdDate", build());
    }

    private static List<FieldDescriptor> build() {
        List<FieldDescriptor> f = new ArrayList<>();
        f.add(FieldDescriptor.of("id", FieldType.ID));
        f.addAll(projectFields("offer.project"));
        f.add(FieldDescriptor.of("offerRevision", FieldType.NUMBER));
        f.add(FieldDescriptor.of("roundNo", FieldType.NUMBER));
        f.add(string("initiatorRole", "initiatorRole"));
        f.add(enumField("kind", "kind", NegotiationRoundKind.class));
        f.add(enumField("scope", "scope", DiscountScope.class));
        f.add(enumField("valueKind", "valueKind", DiscountKind.class));
        // percent or absolute amount depending on valueKind, hence a plain number
        f.add(FieldDescriptor.of("value", FieldType.NUMBER));
        f.add(enumField("status", "status", NegotiationRoundStatus.class));
        f.add(FieldDescriptor.of("adminApproved", FieldType.BOOLEAN).withFlags(true, true, true, false));
        f.add(FieldDescriptor.of("createdDate", FieldType.DATETIME));
        return f;
    }
}
