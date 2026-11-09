package com.foremen.service.dashboard.dataset.estimate;

import com.foremen.dao.model.CompanyProfileEntity;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.foremen.service.dashboard.dataset.estimate.EstimateDatasetSupport.*;

/** Single-row company profile. E-mail, NIP and REGON are NOT exposed. */
@Component
public class CompanyProfileDataset extends AbstractEstimateDataset {

    public CompanyProfileDataset() {
        super("COMPANY_PROFILE", "COMPANY_PROFILE", CompanyProfileEntity.class, null, null, List.of(
                string("fullName", "fullName"),
                string("registeredAddress", "registeredAddress"),
                string("representativeName", "representativeName"),
                string("representativeRole", "representativeRole")));
    }
}
