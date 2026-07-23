-- PlayerInstruction (instrução pessoal): an optional per-player instruction on a lineup row that
-- shifts engine weights/multipliers for that one player. Nullable with no default and no backfill,
-- so every existing (and AI) lineup player resolves to "no instruction" and stays byte-identical.
ALTER TABLE lineup_players ADD COLUMN instruction VARCHAR(20) NULL;
