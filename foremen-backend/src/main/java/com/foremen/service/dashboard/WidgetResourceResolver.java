package com.foremen.service.dashboard;

import com.foremen.dao.model.DashboardWidgetEntity;

import java.util.Set;

/**
 * Resolves a widget's Required_Resources (the union of the resources of its dataset). Implemented by the
 * dataset registry (FOR-10 dataset framework); kept as a seam so the layout service does not depend on it.
 */
@FunctionalInterface
public interface WidgetResourceResolver {

    Set<String> requiredResources(DashboardWidgetEntity widget);
}
