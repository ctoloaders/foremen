package com.foremen.service.image;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Small DB-reference lookup for image object keys, shared by the shared {@link ImageStorage}
 * seam (FOR-04-17, Requirement 7). It answers the single question the orphan-cleanup logic needs:
 * "is this object key still referenced by any database row?", counting references across the
 * UNION of every image column in the schema — currently {@code construction_materials.image} and
 * {@code material_producers.image}.
 *
 * <p>This helper is deliberately extracted so it can be reused by BOTH the eager
 * {@link GcsImageStorage#deleteIfOrphan(String)} trigger (Requirement 7.8, 7.9) and the periodic
 * reconciliation job (task 3.5, Requirement 7.10): the job needs the full set of DB-referenced keys
 * to subtract from the bucket listing, while the eager path needs a per-key referenced check.
 *
 * <p>Queries are native SQL, not JPA, on purpose: the {@code material_producers.image} column and
 * its JPA mapping are added by sibling tasks (8.1/8.2, changeset {@code 059}) and may not yet
 * exist. Each image column is therefore guarded by an {@code information_schema.columns} existence
 * check so this lookup degrades gracefully — a not-yet-migrated column simply contributes no
 * references rather than failing the query.
 */
@Component
public class ImageReferenceLookup {

    /**
     * The image columns that may reference a stored object key, as (table, column) pairs. Every
     * new entity that gains an image column (e.g. FOR-04-18 finishing materials) should be added
     * here so both the eager cleanup and the reconciliation job see its references.
     */
    private static final List<ImageColumn> IMAGE_COLUMNS = List.of(
            new ImageColumn("construction_materials", "image"),
            new ImageColumn("material_producers", "image"));

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Whether any database row references the given object key across all known image columns.
     *
     * @param objectKey the bucket-relative object key to check; {@code null}/blank is treated as
     *                  unreferenced
     * @return {@code true} when at least one row in any image column holds {@code objectKey}
     */
    @Transactional(readOnly = true)
    public boolean isReferenced(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return false;
        }
        for (ImageColumn column : IMAGE_COLUMNS) {
            if (!columnExists(column)) {
                continue;
            }
            String sql = "SELECT COUNT(*) FROM " + column.table()
                    + " WHERE " + column.column() + " = :key";
            Query query = entityManager.createNativeQuery(sql);
            query.setParameter("key", objectKey);
            long count = ((Number) query.getSingleResult()).longValue();
            if (count > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The full set of object keys referenced by any database row across all known image columns.
     * Reused by the reconciliation job (task 3.5) to subtract from the bucket object listing.
     *
     * @return the distinct, non-null object keys referenced anywhere in the schema
     */
    @Transactional(readOnly = true)
    public Set<String> referencedKeys() {
        Set<String> keys = new HashSet<>();
        for (ImageColumn column : IMAGE_COLUMNS) {
            if (!columnExists(column)) {
                continue;
            }
            String sql = "SELECT DISTINCT " + column.column() + " FROM " + column.table()
                    + " WHERE " + column.column() + " IS NOT NULL";
            @SuppressWarnings("unchecked")
            List<Object> rows = entityManager.createNativeQuery(sql).getResultList();
            for (Object row : rows) {
                if (row != null) {
                    keys.add(row.toString());
                }
            }
        }
        return keys;
    }

    /**
     * Whether the given image column currently exists in the database, guarding against a column
     * (e.g. {@code material_producers.image}) whose additive migration has not yet run.
     */
    private boolean columnExists(ImageColumn column) {
        Query query = entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = :table AND column_name = :column");
        query.setParameter("table", column.table());
        query.setParameter("column", column.column());
        return ((Number) query.getSingleResult()).longValue() > 0;
    }

    /** A (table, column) pair identifying an image-key column in the schema. */
    private record ImageColumn(String table, String column) {
    }
}
