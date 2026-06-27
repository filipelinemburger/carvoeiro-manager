ALTER TABLE clubs
  ADD COLUMN balance                  NUMERIC(15,2) NOT NULL DEFAULT 0,
  ADD COLUMN reputation               INT           NOT NULL DEFAULT 0,
  ADD COLUMN stadium_capacity         INT           NOT NULL DEFAULT 0,
  ADD COLUMN sponsorship_per_round    NUMERIC(12,2) NOT NULL DEFAULT 0,
  ADD COLUMN stadium_upkeep_per_round NUMERIC(12,2) NOT NULL DEFAULT 0;

ALTER TABLE players
  ADD COLUMN wage NUMERIC(12,2) NOT NULL DEFAULT 0;

CREATE TABLE finance_transaction (
    id           UUID PRIMARY KEY,
    save_game_id UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    club_id      UUID NOT NULL,
    season_year  INT NOT NULL,
    round        INT NOT NULL,
    type         VARCHAR(16) NOT NULL,
    amount       NUMERIC(15,2) NOT NULL,
    description  VARCHAR(120) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_finance_transaction_save_club ON finance_transaction(save_game_id, club_id);
