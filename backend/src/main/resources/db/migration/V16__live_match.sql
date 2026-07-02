-- One in-progress live match per save game. Deleted on finish; cascades on account/save deletion.
CREATE TABLE live_matches (
    id                UUID PRIMARY KEY,
    save_game_id      UUID NOT NULL UNIQUE REFERENCES save_games(id) ON DELETE CASCADE,
    fixture_id        UUID NOT NULL,
    competition_id    UUID NOT NULL,
    round             INT NOT NULL,
    match_date        DATE NOT NULL,
    seed              BIGINT NOT NULL,
    managed_is_home   BOOLEAN NOT NULL,
    committed_minute  INT NOT NULL DEFAULT 0,
    home_xi           TEXT NOT NULL,
    away_xi           TEXT NOT NULL,
    home_bench        TEXT NOT NULL,
    away_bench        TEXT NOT NULL,
    sub_schedule      TEXT NOT NULL
);
