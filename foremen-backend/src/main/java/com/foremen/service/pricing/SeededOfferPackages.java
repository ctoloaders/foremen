package com.foremen.service.pricing;

import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.model.OfferPackageEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Small cached provider of the seeded {@code offer_packages} rows, exposed as immutable
 * {@code (id, code, nameRU, namePL)} descriptors and indexed by the numeric {@code offerPackage.id}.
 *
 * <p>The set of offer packages is reference (dictionary) data seeded by FOR-04-10 and changes
 * rarely, so the whole table is loaded <b>once</b> — lazily, on first access — and served from an
 * in-memory cache thereafter, avoiding a per-request query. Three concerns share this one provider
 * so they cannot drift (FOR-04-12b design, "id vs code split"):
 * <ul>
 *   <li><b>Pivot_Key id validation</b> — {@link #existsById(Long)} / {@link #byId(Long)} check that a
 *       {@code prices.{packageId}.{field}} key names an existing package (Requirement 4.6);</li>
 *   <li><b>{@code /metadata} pivot descriptors</b> — {@link #all()} drives one synthetic
 *       {@code prices.{id}.netPrice} descriptor per seeded package, carrying the id and the localized
 *       {@code nameRU}/{@code namePL} label (Requirement 4.7);</li>
 *   <li><b>the mapper's per-package iteration</b> — {@link #all()} is iterated to fill the row DTO's
 *       code-keyed prices map.</li>
 * </ul>
 *
 * <p>The cache is keyed by {@code id} (stable across a {@code code} rename) and preserves the seeded
 * ordering ({@code order_no} then {@code id}) so descriptors and columns render in a deterministic
 * package order.
 */
@Component
public class SeededOfferPackages {

    /** Immutable descriptor of a single seeded offer package. */
    public record OfferPackageInfo(Long id, String code, String nameRU, String namePL) {
    }

    private final OfferPackageDao offerPackageDao;

    /** Populated once on first access; read-only afterwards. Guarded by {@code this}. */
    private volatile List<OfferPackageInfo> cached;
    /** Id-keyed view of {@link #cached}, in insertion (seed) order. */
    private volatile Map<Long, OfferPackageInfo> byId;

    public SeededOfferPackages(OfferPackageDao offerPackageDao) {
        this.offerPackageDao = offerPackageDao;
    }

    /**
     * Returns all seeded offer packages, in seed order ({@code order_no}, then {@code id}), loading
     * them from the database on the first call and serving the cached list thereafter.
     */
    public List<OfferPackageInfo> all() {
        return load();
    }

    /** Returns the descriptor for {@code id}, or empty when no seeded package has that id. */
    public Optional<OfferPackageInfo> byId(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        load();
        return Optional.ofNullable(byId.get(id));
    }

    /** Returns {@code true} when a seeded package with the given numeric {@code offerPackage.id} exists. */
    public boolean existsById(Long id) {
        return byId(id).isPresent();
    }

    private List<OfferPackageInfo> load() {
        List<OfferPackageInfo> local = cached;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cached == null) {
                List<OfferPackageInfo> list = new ArrayList<>();
                Map<Long, OfferPackageInfo> index = new LinkedHashMap<>();
                for (OfferPackageEntity entity : offerPackageDao.findAll()) {
                    OfferPackageInfo info = new OfferPackageInfo(
                            entity.getId(), entity.getCode(), entity.getNameRU(), entity.getNamePL());
                    list.add(info);
                    index.put(info.id(), info);
                }
                list.sort((a, b) -> Long.compare(a.id(), b.id()));
                Map<Long, OfferPackageInfo> ordered = new LinkedHashMap<>();
                for (OfferPackageInfo info : list) {
                    ordered.put(info.id(), info);
                }
                this.byId = Collections.unmodifiableMap(ordered);
                this.cached = Collections.unmodifiableList(list);
            }
            return cached;
        }
    }
}
