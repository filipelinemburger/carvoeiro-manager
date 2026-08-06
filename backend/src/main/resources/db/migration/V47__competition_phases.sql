-- Ordered phases within a competition. A competition with no rows here behaves
-- exactly as before: a single double-round-robin league.
CREATE TABLE competition_phase (
    id             UUID PRIMARY KEY,
    save_game_id   UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    competition_id UUID NOT NULL,
    ordinal        INT  NOT NULL,
    name           VARCHAR(40) NOT NULL,
    kind           VARCHAR(16) NOT NULL,
    legs           INT  NOT NULL,
    rounds         INT  NOT NULL,
    qualifiers     INT
);
CREATE INDEX idx_competition_phase_comp ON competition_phase(competition_id, ordinal);
CREATE INDEX idx_competition_phase_save ON competition_phase(save_game_id);

-- Knockout support on fixtures. All nullable: existing rows stay valid.
ALTER TABLE fixtures ADD COLUMN phase_id     UUID;
ALTER TABLE fixtures ADD COLUMN leg          INT;
ALTER TABLE fixtures ADD COLUMN tie_id       UUID;
-- 0-based pairing index within the phase. tie_id is an opaque UUID and sorts
-- arbitrarily, so this is what preserves seeded bracket order across rounds.
ALTER TABLE fixtures ADD COLUMN tie_order    INT;
ALTER TABLE fixtures ADD COLUMN penalty_home INT;
ALTER TABLE fixtures ADD COLUMN penalty_away INT;
CREATE INDEX idx_fixtures_phase ON fixtures(phase_id);
CREATE INDEX idx_fixtures_tie   ON fixtures(tie_id);

-- A reserved knockout MATCH event knows its phase before it knows its fixtures.
ALTER TABLE season_event ADD COLUMN phase_id UUID;
