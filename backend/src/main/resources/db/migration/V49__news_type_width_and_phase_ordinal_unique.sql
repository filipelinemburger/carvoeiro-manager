-- PHASE_ELIMINATED is exactly 16 characters, so news_item.type had zero headroom on an untyped
-- String column with no guard. This repo has already been bitten twice by an enum name outgrowing
-- its column (COMPETITION_CHAMPION at 20, caught before it shipped; the shootout event names at 23,
-- which needed V48). Postgres widens a varchar without rewriting the table.
ALTER TABLE news_item ALTER COLUMN type TYPE VARCHAR(32);

-- PhaseResolver finds the next phase by ordinal + 1. A duplicate ordinal would make it pick an
-- arbitrary one of the pair, silently materializing the wrong round.
CREATE UNIQUE INDEX idx_competition_phase_comp_ordinal_unique
    ON competition_phase(competition_id, ordinal);
