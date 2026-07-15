CREATE TABLE staff (
    id            UUID PRIMARY KEY,
    save_game_id  UUID NOT NULL,
    club_id       UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    role          VARCHAR(20) NOT NULL,
    quality       VARCHAR(10) NOT NULL,
    name          VARCHAR(80) NOT NULL,
    wage          NUMERIC(14,2) NOT NULL,
    UNIQUE (club_id, role)
);

CREATE INDEX idx_staff_club ON staff(club_id);
CREATE INDEX idx_staff_save_game ON staff(save_game_id);
