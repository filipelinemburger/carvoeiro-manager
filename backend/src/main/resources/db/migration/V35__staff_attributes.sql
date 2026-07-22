-- CM-style 1-20 attributes per staff member; meaning is role-dependent (labels live in code).
-- Backfill from the quality tier midpoint so existing hires keep today's effect sizes.
ALTER TABLE staff ADD COLUMN primary_attribute INT;
ALTER TABLE staff ADD COLUMN secondary_attribute INT;

UPDATE staff SET
    primary_attribute = CASE quality
        WHEN 'REGULAR' THEN 10 WHEN 'BOM' THEN 14 WHEN 'EXCELENTE' THEN 17 END,
    secondary_attribute = CASE quality
        WHEN 'REGULAR' THEN 10 WHEN 'BOM' THEN 14 WHEN 'EXCELENTE' THEN 17 END;

ALTER TABLE staff ALTER COLUMN primary_attribute SET NOT NULL;
ALTER TABLE staff ALTER COLUMN secondary_attribute SET NOT NULL;
ALTER TABLE staff ADD CONSTRAINT staff_primary_attribute_range CHECK (primary_attribute BETWEEN 1 AND 20);
ALTER TABLE staff ADD CONSTRAINT staff_secondary_attribute_range CHECK (secondary_attribute BETWEEN 1 AND 20);
