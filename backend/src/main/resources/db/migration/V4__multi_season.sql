CREATE TABLE season (
    id                    UUID PRIMARY KEY,
    save_game_id          UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    competition_id        UUID NOT NULL,
    season_year           INT NOT NULL,
    competition_name      VARCHAR(80) NOT NULL,
    champion_club_id      UUID NOT NULL,
    champion_club_name    VARCHAR(80) NOT NULL,
    managed_club_id       UUID NOT NULL,
    managed_club_name     VARCHAR(80) NOT NULL,
    managed_club_position INT NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (save_game_id, season_year, competition_id)
);

CREATE TABLE season_standing (
    id               UUID PRIMARY KEY,
    season_id        UUID NOT NULL REFERENCES season(id) ON DELETE CASCADE,
    position         INT NOT NULL,
    club_id          UUID NOT NULL,
    club_name        VARCHAR(80) NOT NULL,
    played           INT NOT NULL,
    won              INT NOT NULL,
    drawn            INT NOT NULL,
    lost             INT NOT NULL,
    goals_for        INT NOT NULL,
    goals_against    INT NOT NULL,
    goal_difference  INT NOT NULL,
    points           INT NOT NULL
);

CREATE TABLE season_top_scorer (
    id           UUID PRIMARY KEY,
    season_id    UUID NOT NULL REFERENCES season(id) ON DELETE CASCADE,
    rank         INT NOT NULL,
    player_id    UUID NOT NULL,
    player_name  VARCHAR(80) NOT NULL,
    club_id      UUID NOT NULL,
    club_name    VARCHAR(80) NOT NULL,
    goals        INT NOT NULL
);

CREATE INDEX idx_season_save_game ON season(save_game_id);
CREATE INDEX idx_season_standing_season ON season_standing(season_id);
CREATE INDEX idx_season_top_scorer_season ON season_top_scorer(season_id);
