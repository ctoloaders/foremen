package com.foremen.service.dashboard.dataset.estimate;

import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.foremen.service.dashboard.dataset.estimate.EstimateDatasetSupport.*;

/** Client offers (discounts and negotiation details live in the negotiation-rounds dataset). */
@Component
public class OffersDataset extends AbstractEstimateDataset {

    private static final String CUR = "estimate.currency.code";

    public OffersDataset() {
        super("OFFERS", "OFFERS", OfferEntity.class, "project.id", "createdDate", build());
    }

    private static List<FieldDescriptor> build() {
        List<FieldDescriptor> f = new ArrayList<>();
        f.add(FieldDescriptor.of("id", FieldType.ID));
        f.addAll(projectFields("project"));
        f.add(enumField("status", "status", OfferStatus.class));
        f.add(FieldDescriptor.of("revision", FieldType.NUMBER));
        f.add(FieldDescriptor.of("approvedRevision", FieldType.NUMBER));
        f.add(string("selectedPackage.nameRU", "selectedPackage.nameRU"));
        f.add(string("selectedPackage.namePL", "selectedPackage.namePL"));
        f.add(money("totalNet", "totalNet", CUR));
        f.add(money("totalVat", "totalVat", CUR));
        f.add(money("totalGross", "totalGross", CUR));
        f.add(FieldDescriptor.of("createdDate", FieldType.DATETIME));
        return f;
    }
}
