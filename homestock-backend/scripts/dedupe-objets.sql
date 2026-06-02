-- Removes the 11 duplicate objets identified in the 2026-06-01 export.
-- For each pair (created by a fast double-tap on "Enregistrer"), keeps the
-- row with the lower id and deletes the higher one. Idempotent: re-running
-- the script after the dup ids are gone is a no-op.
--
-- Usage on the NAS:
--   docker exec -i homestock-db psql -U homestock -d homestock < dedupe-objets.sql
-- or paste the body between BEGIN; / COMMIT; into psql interactively.

BEGIN;

-- vins are FK-cascade-deleted server-side, but we delete them explicitly here
-- so the script also works if you ever loosen the cascade. None of the dup
-- objets are wines today, so this is a no-op in practice.
DELETE FROM vins
 WHERE objet_id IN (6, 8, 14, 16, 18, 23, 25, 27, 29, 33, 35);

DELETE FROM objets
 WHERE id IN (6, 8, 14, 16, 18, 23, 25, 27, 29, 33, 35);

COMMIT;

-- Sanity check: should return 0.
SELECT COUNT(*) AS still_dup
  FROM (
    SELECT nom, emplacement_id, categorie, COUNT(*) c
      FROM objets
     GROUP BY nom, emplacement_id, categorie
    HAVING COUNT(*) > 1
  ) t;
