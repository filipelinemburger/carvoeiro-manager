CREATE TABLE competition_club (
    id             UUID PRIMARY KEY,
    save_game_id   UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    competition_id UUID NOT NULL,
    club_id        UUID NOT NULL
);
CREATE INDEX idx_competition_club_comp ON competition_club(competition_id);
CREATE INDEX idx_competition_club_save ON competition_club(save_game_id);

CREATE TABLE season_event (
    id             UUID PRIMARY KEY,
    save_game_id   UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    event_date     DATE NOT NULL,
    type           VARCHAR(12) NOT NULL,
    competition_id UUID,
    round          INT,
    sequence       INT NOT NULL,
    processed      BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_season_event_next ON season_event(save_game_id, processed, event_date, sequence);

ALTER TABLE fixtures ADD COLUMN match_date DATE;
ALTER TABLE save_games ADD COLUMN "current_date" DATE;
