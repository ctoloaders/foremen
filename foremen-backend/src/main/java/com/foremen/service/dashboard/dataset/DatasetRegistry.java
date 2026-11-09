package com.foremen.service.dashboard.dataset;

import com.foremen.service.dashboard.config.DatasetSchema;
import com.foremen.service.dashboard.config.DatasetSchemaLookup;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Registry of all {@link Dataset} beans (FOR-10 Requirement 6). Validates every dataset at
 * construction time and fails application startup when a project/owner path or a field path does not
 * resolve against the JPA metamodel, so a broken dataset can never be served.
 */
@Component
public class DatasetRegistry implements DatasetSchemaLookup {

    private final Map<String, Dataset> datasets;

    @Autowired
    public DatasetRegistry(ObjectProvider<Dataset> beans, EntityManagerFactory emf) {
        this(beans.orderedStream().toList(), emf.getMetamodel());
    }

    public DatasetRegistry(Collection<Dataset> all, Metamodel metamodel) {
        Map<String, Dataset> byCode = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        for (Dataset d : all) {
            problems.addAll(validate(d, metamodel));
            if (d.code() != null && byCode.putIfAbsent(d.code(), d) != null) {
                problems.add("duplicate dataset code " + d.code());
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid dashboard datasets: " + String.join("; ", problems));
        }
        this.datasets = Map.copyOf(byCode);
    }

    private static List<String> validate(Dataset d, Metamodel mm) {
        List<String> out = new ArrayList<>();
        String code = d.code();
        if (code == null || code.isBlank()) {
            out.add("dataset " + d.getClass().getName() + " has no code");
            return out;
        }
        if (d.rootEntity() == null) {
            out.add(code + ": rootEntity is required");
            return out;
        }
        d.projectPath().ifPresent(p -> pathProblem(out, code, "projectPath", mm, d.rootEntity(), p));
        d.ownerPath().ifPresent(p -> pathProblem(out, code, "ownerPath", mm, d.rootEntity(), p));
        if ((d.alwaysOwn() || !d.ownerRestrictedRoles().isEmpty()) && d.ownerPath().isEmpty()) {
            out.add(code + ": owner restriction declared without ownerPath");
        }
        Set<String> names = new HashSet<>();
        for (FieldDescriptor f : d.allFields()) {
            if (!names.add(f.name())) {
                out.add(code + ": duplicate field " + f.name());
            }
            if (f.jpaPath() != null) {
                pathProblem(out, code, "field " + f.name(), mm, d.rootEntity(), f.jpaPath());
            }
            if (f.currencyPath() != null) {
                pathProblem(out, code, "currencyPath of " + f.name(), mm, d.rootEntity(), f.currencyPath());
            }
        }
        d.defaultDateField().ifPresent(n -> {
            if (!names.contains(n)) {
                out.add(code + ": defaultDateField '" + n + "' is not a field");
            }
        });
        return out;
    }

    private static void pathProblem(List<String> out, String code, String what, Metamodel mm,
                                    Class<?> root, String path) {
        String problem = JpaPaths.check(mm, root, path);
        if (problem != null) {
            out.add(code + ": " + what + " '" + path + "' does not resolve (" + problem + ")");
        }
    }

    public Optional<Dataset> get(String code) {
        return Optional.ofNullable(datasets.get(code));
    }

    public List<Dataset> all() {
        return datasets.values().stream().sorted(Comparator.comparing(Dataset::code)).toList();
    }

    /** Catalog for the admin builder, sorted by code. */
    public List<DatasetCatalogDto> catalog() {
        return all().stream().map(DatasetCatalogDto::from).toList();
    }

    @Override
    public Optional<DatasetSchema> find(String datasetCode) {
        return get(datasetCode).map(Dataset::schema);
    }
}
