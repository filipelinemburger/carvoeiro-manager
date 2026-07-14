CREATE TABLE fixture_appearance (
    id              UUID PRIMARY KEY,
    fixture_id      UUID NOT NULL REFERENCES fixtures(id) ON DELETE CASCADE,
    player_id       UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    club_id         UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    minutes_played  INT NOT NULL,
    fouls           INT NOT NULL DEFAULT 0
);

CREATE TABLE season_player_stats (
    id             UUID PRIMARY KEY,
    season_id      UUID NOT NULL REFERENCES season(id) ON DELETE CASCADE,
    player_id      UUID NOT NULL,
    player_name    VARCHAR(80) NOT NULL,
    club_id        UUID NOT NULL,
    club_name      VARCHAR(80) NOT NULL,
    matches_played INT NOT NULL,
    minutes_played INT NOT NULL,
    goals          INT NOT NULL,
    assists        INT NOT NULL,
    fouls          INT NOT NULL,
    yellow_cards   INT NOT NULL,
    red_cards      INT NOT NULL
);

CREATE INDEX idx_fixture_appearance_fixture ON fixture_appearance(fixture_id);
CREATE INDEX idx_fixture_appearance_player ON fixture_appearance(player_id);
CREATE INDEX idx_season_player_stats_season ON season_player_stats(season_id);
CREATE INDEX idx_season_player_stats_player ON season_player_stats(player_id);
