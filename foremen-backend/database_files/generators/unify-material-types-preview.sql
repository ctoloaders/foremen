-- PREVIEW ONLY — unify finishing material_types onto the catalog code (FOR-04-19 data gap).
-- material_types.code is UNIQUE + referenced by finishing_materials.type_id and
-- work_material_consumptions.finishing_material_type_id, so a merge = repoint the FK
-- onto the surviving catalog type id, then delete the duplicate norm-side type row.
-- Only EXACT semantic pairs are merged. Review before applying (docker exec ... psql -f).
--
-- Applied merges: 1 (PODKLAD -> podklad).
-- Disputed / NOT merged: LISTWA_PRZYPODLOGOWA -> styrodur (plinth vs styrodur-plinth).
-- Norm-orphan types (14) have no catalog material at all — merging cannot fix them;
-- they need real finishing materials seeded (out of scope of this normalization).

BEGIN;
UPDATE work_material_consumptions SET finishing_material_type_id=7 WHERE finishing_material_type_id=53;
DELETE FROM material_types WHERE id=53 AND code='PODKLAD';
COMMIT;
