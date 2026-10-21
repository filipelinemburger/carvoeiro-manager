-- Board objectives and job security (spec 2026-10-21). The board is its own feature package, so
-- its state lives in its own tables rather than as more columns on save_games.
CREATE TABLE board_states (
    save_game_id            UUID PRIMARY KEY REFERENCES save_games(id) ON DELETE CASCADE,
    confidence              INT     NOT NULL,
    ultimatum_active        BOOLEAN NOT NULL DEFAULT FALSE,
    matches_since_ultimatum INT     NOT NULL DEFAULT 0,
    sacking_enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    grace_season_year       INT
);

-- Kept for every season: past objectives are the manager's record (the sacked screen lists them).
CREATE TABLE board_objectives (
    id              UUID PRIMARY KEY,
    save_game_id    UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    season_year     INT  NOT NULL,
    kind            VARCHAR(20) NOT NULL,
    competition_id  UUID,
    tier            INT,
    target_position INT,
    target_stage    VARCHAR(20),
    outcome         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    UNIQUE (save_game_id, season_year, kind)
);

-- Season-scoped: purged at rollover with the news feed.
CREATE TABLE board_confidence_changes (
    id               UUID PRIMARY KEY,
    save_game_id     UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    event_date       DATE NOT NULL,
    seq              INT  NOT NULL,
    reason           VARCHAR(40) NOT NULL,
    delta            INT  NOT NULL,
    confidence_after INT  NOT NULL,
    payload          TEXT
);
CREATE INDEX idx_board_changes_save_seq ON board_confidence_changes (save_game_id, seq DESC);

-- Competitions are found by scope and divisions by tier, never by name (spec §5.5). This backfill
-- is the only place that matches by name, and only for saves created before the columns existed.
ALTER TABLE competitions ADD COLUMN scope VARCHAR(10) NOT NULL DEFAULT 'NATIONAL';
ALTER TABLE competitions ADD COLUMN tier  INT;
UPDATE competitions SET scope = 'STATE' WHERE name = 'Campeonato Catarinense';
UPDATE competitions SET tier = 1 WHERE name = 'Série A';
UPDATE competitions SET tier = 2 WHERE name = 'Série B';
UPDATE competitions SET tier = 3 WHERE name = 'Série C';
