package com.foremen.service.dashboard.config;

import java.util.Optional;

/** Resolves a dataset code to its {@link DatasetSchema}. Implemented by the dataset registry (task 2.3). */
@FunctionalInterface
public interface DatasetSchemaLookup {

    Optional<DatasetSchema> find(String datasetCode);
}
