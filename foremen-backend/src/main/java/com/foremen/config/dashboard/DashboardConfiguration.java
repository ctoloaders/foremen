package com.foremen.config.dashboard;

import com.foremen.service.dashboard.config.DatasetSchemaLookup;
import com.foremen.service.dashboard.config.WidgetConfigValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the Widget_Config validator to the dataset registry (FOR-10). */
@Configuration
public class DashboardConfiguration {

    @Bean
    public WidgetConfigValidator widgetConfigValidator(DatasetSchemaLookup datasets) {
        return new WidgetConfigValidator(datasets);
    }
}
