package com.foremen.service.dashboard;

import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.service.dashboard.dataset.DatasetRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * {@link WidgetResourceResolver} backed by the {@link DatasetRegistry}: a widget's Required_Resources are
 * those of the dataset named in its config. An unknown dataset resolves to no resources (the fetch then
 * fails on config resolution rather than silently granting data).
 */
@Component
public class DatasetWidgetResourceResolver implements WidgetResourceResolver {

    private final DatasetRegistry registry;

    public DatasetWidgetResourceResolver(DatasetRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Set<String> requiredResources(DashboardWidgetEntity widget) {
        Object code = widget.getConfig() == null ? null : widget.getConfig().get("dataset");
        if (!(code instanceof String s)) {
            return Set.of();
        }
        return registry.get(s).map(d -> Set.copyOf(d.requiredResources())).orElse(Set.of());
    }
}
