CREATE TABLE save_games (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES users(id),
    managed_club_id  UUID,
    season_year      INT NOT NULL,
    current_round    INT NOT NULL DEFAULT 0,
    status           VARCHAR(20) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE clubs (
    id            UUID PRIMARY KEY,
    save_game_id  UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    name          VARCHAR(80) NOT NULL,
    short_name    VARCHAR(20) NOT NULL,
    nickname      VARCHAR(40),
    primary_color VARCHAR(9),
    secondary_color VARCHAR(9)
);

CREATE TABLE players (
    id            UUID PRIMARY KEY,
    save_game_id  UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    club_id       UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    name          VARCHAR(80) NOT NULL,
    position      VARCHAR(3) NOT NULL,   -- GK, DEF, MID, ATT
    age           INT NOT NULL,
    nationality   VARCHAR(40) NOT NULL,
    pace          INT NOT NULL,
    finishing     INT NOT NULL,
    passing       INT NOT NULL,
    tackling      INT NOT NULL,
    goalkeeping   INT NOT NULL,
    stamina       INT NOT NULL,
    overall       INT NOT NULL
);

CREATE TABLE competitions (
    id            UUID PRIMARY KEY,
    save_game_id  UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    name          VARCHAR(80) NOT NULL,
    type          VARCHAR(20) NOT NULL,  -- LEAGUE
    season_year   INT NOT NULL,
    total_rounds  INT NOT NULL
);

CREATE TABLE fixtures (
    id             UUID PRIMARY KEY,
    save_game_id   UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    competition_id UUID NOT NULL REFERENCES competitions(id) ON DELETE CASCADE,
    round          INT NOT NULL,
    home_club_id   UUID NOT NULL REFERENCES clubs(id),
    away_club_id   UUID NOT NULL REFERENCES clubs(id),
    home_goals     INT,
    away_goals     INT,
    played         BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE match_events (
    id          UUID PRIMARY KEY,
    fixture_id  UUID NOT NULL REFERENCES fixtures(id) ON DELETE CASCADE,
    minute      INT NOT NULL,
    type        VARCHAR(20) NOT NULL,   -- GOAL
    player_id   UUID NOT NULL REFERENCES players(id),
    club_id     UUID NOT NULL REFERENCES clubs(id)
);

CREATE TABLE lineups (
    id            UUID PRIMARY KEY,
    save_game_id  UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    club_id       UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    formation     VARCHAR(10) NOT NULL,
    UNIQUE (save_game_id, club_id)
);

CREATE TABLE lineup_players (
    lineup_id  UUID NOT NULL REFERENCES lineups(id) ON DELETE CASCADE,
    player_id  UUID NOT NULL REFERENCES players(id),
    starting   BOOLEAN NOT NULL,
    PRIMARY KEY (lineup_id, player_id)
);

CREATE INDEX idx_fixtures_round ON fixtures(save_game_id, competition_id, round);
CREATE INDEX idx_players_club ON players(club_id);
