package com.foremen.service.dashboard.dataset.projects;

import org.springframework.stereotype.Component;

import java.util.Set;

/** FOR-10 dataset {@code SCHEDULE_BARS}: project work-schedule bars (resource WORK_SCHEDULE). */
@Component
public class ScheduleBarsDataset extends AbstractScheduleBarsDataset {

    public static final String CODE = "SCHEDULE_BARS";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Set<String> requiredResources() {
        return Set.of("WORK_SCHEDULE");
    }
}
