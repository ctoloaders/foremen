package com.foremen.service.dashboard.dataset.projects;

import org.springframework.stereotype.Component;

import java.util.Set;

/** FOR-10 dataset {@code COMPANY_SCHEDULE_BARS}: cross-project view (resource COMPANY_SCHEDULE). */
@Component
public class CompanyScheduleBarsDataset extends AbstractScheduleBarsDataset {

    public static final String CODE = "COMPANY_SCHEDULE_BARS";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Set<String> requiredResources() {
        return Set.of("COMPANY_SCHEDULE");
    }
}
