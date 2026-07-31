-- V45 made climate_zone NOT NULL without a DEFAULT, so any INSERT that omits the column fails.
-- Match the Club entity's own field default so every insert path — JPA, raw JDBC, tooling —
-- lands on the same neutral zone.
ALTER TABLE clubs ALTER COLUMN climate_zone SET DEFAULT 'SUDESTE';
